package bdv.viewer.render;

import bdv.cache.CacheControl;
import bdv.img.cache.VolatileCachedCellImg;
import bdv.util.MipmapTransforms;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.Volatile;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.ARGBType;

class ProjectorFactory
{
	/**
	 * How many threads to use for rendering.
	 */
	private final int numRenderingThreads;

	/**
	 * {@link ExecutorService} used for rendering.
	 */
	private final ExecutorService renderingExecutorService;

	/**
	 * Whether volatile versions of sources should be used if available.
	 */
	private final boolean useVolatileIfAvailable;

	/**
	 * TODO javadoc
	 */
	private final AccumulateProjectorFactory< ARGBType > accumulateProjectorFactory;


	/**
	 * TODO revise javadoc
	 * Whether a repaint was {@link #requestRepaint() requested}. This will
	 * cause {@link CacheControl#prepareNextFrame()}.
	 */
	private boolean newFrameRequest;

	/**
	 * The timepoint for which last a projector was
	 * {@link #createProjector created}.
	 */
	private int previousTimepoint;



	// TODO: should be settable
	private final boolean prefetchCells = true;

	ProjectorFactory(
			final int numRenderingThreads,
			final ExecutorService renderingExecutorService,
			final boolean useVolatileIfAvailable,
			final AccumulateProjectorFactory< ARGBType > accumulateProjectorFactory )
	{
		this.numRenderingThreads = numRenderingThreads;
		this.renderingExecutorService = renderingExecutorService;
		this.useVolatileIfAvailable = useVolatileIfAvailable;
		this.accumulateProjectorFactory = accumulateProjectorFactory;
	}

	public VolatileProjector createProjector(
			final ViewerState viewerState,
			final RandomAccessibleInterval< ARGBType > screenImage,
			final AffineTransform3D screenTransform,
			final MultiResolutionRenderer.RenderImage[] renderImages,
			final byte[][] renderMaskArrays )
	{
		/*
		 * This shouldn't be necessary, with
		 * CacheHints.LoadingStrategy==VOLATILE
		 */
//			CacheIoTiming.getIoTimeBudget().clear(); // clear time budget such that prefetching doesn't wait for loading blocks.
		newFrameRequest = false;

		final ArrayList< SourceAndConverter< ? > > visibleSources = new ArrayList<>( viewerState.getVisibleAndPresentSources() );
		visibleSources.sort( viewerState.sourceOrder() );
		VolatileProjector projector;
		if ( visibleSources.isEmpty() )
			projector = new EmptyProjector<>( screenImage );
		else if ( visibleSources.size() == 1 )
		{
			projector = createSingleSourceProjector( viewerState, visibleSources.get( 0 ), screenImage, screenTransform, renderMaskArrays[ 0 ] );
		}
		else
		{
			final ArrayList< VolatileProjector > sourceProjectors = new ArrayList<>();
			final ArrayList< MultiResolutionRenderer.RenderImage > sourceImages = new ArrayList<>();
			int j = 0;
			for ( final SourceAndConverter< ? > source : visibleSources )
			{
				final MultiResolutionRenderer.RenderImage renderImage = renderImages[ j ];
				final byte[] maskArray = renderMaskArrays[ j ];
				++j;
				final VolatileProjector p = createSingleSourceProjector( viewerState, source, renderImage, screenTransform, maskArray );
				sourceProjectors.add( p );
				sourceImages.add( renderImage );
			}
			projector = accumulateProjectorFactory.createProjector( sourceProjectors, visibleSources, sourceImages, screenImage, numRenderingThreads, renderingExecutorService );
		}
		previousTimepoint = viewerState.getCurrentTimepoint();
		return projector;
	}

	private < T > VolatileProjector createSingleSourceProjector(
			final ViewerState viewerState,
			final SourceAndConverter< T > source,
			final RandomAccessibleInterval< ARGBType > screenImage,
			final AffineTransform3D screenTransform,
			final byte[] maskArray )
	{
		if ( useVolatileIfAvailable )
		{
			if ( source.asVolatile() != null )
				return createSingleSourceVolatileProjector( viewerState, source.asVolatile(), screenImage, screenTransform, maskArray );
			else if ( source.getSpimSource().getType() instanceof Volatile )
			{
				@SuppressWarnings( "unchecked" )
				final SourceAndConverter< ? extends Volatile< ? > > vsource = ( SourceAndConverter< ? extends Volatile< ? > > ) source;
				return createSingleSourceVolatileProjector( viewerState, vsource, screenImage, screenTransform, maskArray );
			}
		}

		final int bestLevel = getBestMipMapLevel( viewerState, source, screenTransform );
		return new SimpleVolatileProjector<>(
				getTransformedSource( viewerState, source.getSpimSource(), screenTransform, bestLevel, null ),
				source.getConverter(), screenImage, numRenderingThreads, renderingExecutorService );
	}

	private < T extends Volatile< ? > > VolatileProjector createSingleSourceVolatileProjector(
			final ViewerState viewerState,
			final SourceAndConverter< T > source,
			final RandomAccessibleInterval< ARGBType > screenImage,
			final AffineTransform3D screenTransform,
			final byte[] maskArray )
	{
		final ArrayList< RandomAccessible< T > > renderList = new ArrayList<>();
		final Source< T > spimSource = source.getSpimSource();
		final int t = viewerState.getCurrentTimepoint();

		final MipmapOrdering ordering = spimSource instanceof MipmapOrdering ?
				( MipmapOrdering ) spimSource : new DefaultMipmapOrdering( spimSource );

		final MipmapOrdering.MipmapHints hints = ordering.getMipmapHints( screenTransform, t, previousTimepoint );
		final List< MipmapOrdering.Level > levels = hints.getLevels();

		if ( prefetchCells )
		{
			levels.sort( MipmapOrdering.prefetchOrderComparator );
			for ( final MipmapOrdering.Level l : levels )
			{
				final CacheHints cacheHints = l.getPrefetchCacheHints();
				if ( cacheHints == null || cacheHints.getLoadingStrategy() != LoadingStrategy.DONTLOAD )
					prefetch( viewerState, spimSource, screenTransform, l.getMipmapLevel(), cacheHints, screenImage );
			}
		}

		levels.sort( MipmapOrdering.renderOrderComparator );
		for ( final MipmapOrdering.Level l : levels )
			renderList.add( getTransformedSource( viewerState, spimSource, screenTransform, l.getMipmapLevel(), l.getRenderCacheHints() ) );

		if ( hints.renewHintsAfterPaintingOnce() )
			newFrameRequest = true;

		return new VolatileHierarchyProjector<>( renderList, source.getConverter(), screenImage, maskArray, numRenderingThreads, renderingExecutorService );
	}

	/**
	 * Get the mipmap level that best matches the given screen scale for the given source.
	 *
	 * @param screenTransform
	 * 		transforms screen image coordinates to global coordinates.
	 *
	 * @return mipmap level
	 */
	private static int getBestMipMapLevel(
			final ViewerState viewerState,
			final SourceAndConverter< ? > source,
			final AffineTransform3D screenTransform )
	{
		return MipmapTransforms.getBestMipMapLevel( screenTransform, source.getSpimSource(), viewerState.getCurrentTimepoint() );
	}

	private static < T > RandomAccessible< T > getTransformedSource(
			final ViewerState viewerState,
			final Source< T > source,
			final AffineTransform3D screenTransform,
			final int mipmapIndex,
			final CacheHints cacheHints )
	{
		final int timepoint = viewerState.getCurrentTimepoint();

		final RandomAccessibleInterval< T > img = source.getSource( timepoint, mipmapIndex );
		if ( img instanceof VolatileCachedCellImg )
			( ( VolatileCachedCellImg< ?, ? > ) img ).setCacheHints( cacheHints );

		final Interpolation interpolation = viewerState.getInterpolation();
		final RealRandomAccessible< T > ipimg = source.getInterpolatedSource( timepoint, mipmapIndex, interpolation );

		final AffineTransform3D sourceToScreen = new AffineTransform3D();
		source.getSourceTransform( timepoint, mipmapIndex, sourceToScreen );
		sourceToScreen.preConcatenate( screenTransform );

		return RealViews.affine( ipimg, sourceToScreen );
	}

	private static < T > void prefetch(
			final ViewerState viewerState,
			final Source< T > source,
			final AffineTransform3D screenTransform,
			final int mipmapIndex,
			final CacheHints prefetchCacheHints,
			final Dimensions screenInterval )
	{
		final int timepoint = viewerState.getCurrentTimepoint();
		final RandomAccessibleInterval< T > img = source.getSource( timepoint, mipmapIndex );
		if ( img instanceof VolatileCachedCellImg )
		{
			final VolatileCachedCellImg< ?, ? > cellImg = ( VolatileCachedCellImg< ?, ? > ) img;

			CacheHints hints = prefetchCacheHints;
			if ( hints == null )
			{
				final CacheHints d = cellImg.getDefaultCacheHints();
				hints = new CacheHints( LoadingStrategy.VOLATILE, d.getQueuePriority(), false );
			}
			cellImg.setCacheHints( hints );
			final int[] cellDimensions = new int[ 3 ];
			cellImg.getCellGrid().cellDimensions( cellDimensions );
			final long[] dimensions = new long[ 3 ];
			cellImg.dimensions( dimensions );
			final RandomAccess< ? > cellsRandomAccess = cellImg.getCells().randomAccess();

			final Interpolation interpolation = viewerState.getInterpolation();

			final AffineTransform3D sourceToScreen = new AffineTransform3D();
			source.getSourceTransform( timepoint, mipmapIndex, sourceToScreen );
			sourceToScreen.preConcatenate( screenTransform );

			Prefetcher.fetchCells( sourceToScreen, cellDimensions, dimensions, screenInterval, interpolation, cellsRandomAccess );
		}
	}

	// TODO: naming
	// TODO: only needs to trigger new frame if projector.map() is not complete
	public boolean newFrameRequest()
	{
		return newFrameRequest;
	}
}