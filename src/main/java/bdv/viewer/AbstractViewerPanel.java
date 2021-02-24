package bdv.viewer;

import bdv.util.Affine3DHelpers;
import bdv.viewer.animate.AbstractTransformAnimator;
import bdv.viewer.animate.OverlayAnimator;
import java.awt.Component;
import java.awt.LayoutManager;
import javax.swing.JPanel;
import net.imglib2.Positionable;
import net.imglib2.realtransform.AffineTransform3D;
import org.scijava.listeners.Listeners;
import org.scijava.ui.behaviour.io.InputTriggerConfig;

// TODO: copy javadocs from ViewrPanel
// TODO: actually move functionality here?
public abstract class AbstractViewerPanel extends JPanel implements RequestRepaint
{
	public AbstractViewerPanel( LayoutManager layout, boolean isDoubleBuffered )
	{
		super( layout, isDoubleBuffered );
	}

	public abstract InputTriggerConfig getInputTriggerConfig();

	// TODO: this needs to be more general
	public abstract InteractiveDisplayCanvas getDisplay();
	// --> required: getDisplay().overlays()
	// --> required: getDisplay().addHandler(...)
	// --> required: getDisplay().removeHandler(...)
//	public abstract Component getDisplay();
	// --> required: getDisplay().repaint()

	/**
	 * Add a new {@link OverlayAnimator} to the list of animators. The animation
	 * is immediately started. The new {@link OverlayAnimator} will remain in
	 * the list of animators until it {@link OverlayAnimator#isComplete()}.
	 *
	 * @param animator
	 *            animator to add.
	 */
	public abstract void addOverlayAnimator( OverlayAnimator animator );

	public abstract ViewerState state();

	public abstract Listeners< TransformListener< AffineTransform3D > > renderTransformListeners();

	public abstract Listeners< TransformListener< AffineTransform3D > > transformListeners();



	// introduced for BookmarksEditor
	//   --> is it used anywhere else?
	public abstract void getMouseCoordinates( Positionable p );

	// introduced for BookmarksEditor
	//   --> is it used anywhere else?
	public abstract void setTransformAnimator( AbstractTransformAnimator animator );

	// introduced for ManualTransformationEditor
	//   --> is it used anywhere else?
	public abstract void showMessage( String msg );




	private final static double c = Math.cos( Math.PI / 4 );

	/**
	 * The planes which can be aligned with the viewer coordinate system: XY,
	 * ZY, and XZ plane.
	 */
	public enum AlignPlane
	{
		XY( "XY", 2, new double[] { 1, 0, 0, 0 } ),
		ZY( "ZY", 0, new double[] { c, 0, -c, 0 } ),
		XZ( "XZ", 1, new double[] { c, c, 0, 0 } );

		private final String name;

		public String getName()
		{
			return name;
		}

		/**
		 * rotation from the xy-plane aligned coordinate system to this plane.
		 */
		final double[] qAlign;

		/**
		 * Axis index. The plane spanned by the remaining two axes will be
		 * transformed to the same plane by the computed rotation and the
		 * "rotation part" of the affine source transform.
		 * @see Affine3DHelpers#extractApproximateRotationAffine(AffineTransform3D, double[], int)
		 */
		final int coerceAffineDimension;

		private AlignPlane( final String name, final int coerceAffineDimension, final double[] qAlign )
		{
			this.name = name;
			this.coerceAffineDimension = coerceAffineDimension;
			this.qAlign = qAlign;
		}
	}

	// TODO: implementation should maybe just move here
	protected abstract void align( final AlignPlane plane );
}
