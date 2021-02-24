package bdv.viewer;

import bdv.viewer.animate.OverlayAnimator;
import java.awt.Component;
import java.awt.LayoutManager;
import javax.swing.JPanel;
import org.scijava.ui.behaviour.io.InputTriggerConfig;

public abstract class AbstractViewerPanel extends JPanel implements RequestRepaint
{
	public AbstractViewerPanel( LayoutManager layout, boolean isDoubleBuffered )
	{
		super( layout, isDoubleBuffered );
	}

	public abstract InputTriggerConfig getInputTriggerConfig();

	// TODO: this needs to be moder general
//	public abstract InteractiveDisplayCanvas getDisplay();
	public abstract Component getDisplay();

	/**
	 * Add a new {@link OverlayAnimator} to the list of animators. The animation
	 * is immediately started. The new {@link OverlayAnimator} will remain in
	 * the list of animators until it {@link OverlayAnimator#isComplete()}.
	 *
	 * @param animator
	 *            animator to add.
	 */
	public abstract void addOverlayAnimator( OverlayAnimator animator );
}
