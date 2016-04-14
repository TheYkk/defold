package com.defold.editor.eclipse;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.GapTextStore;
import org.eclipse.jface.text.GapTextStore;
import org.eclipse.jface.text.CopyOnWriteTextStore;
import org.eclipse.jface.text.DefaultLineTracker;

/**
 *Custom implementation of Document to remove unneed dependencies like
 *SafeRunner and org/osgi/framework/BundleActivator
 */
public class Document extends AbstractDocument {
	/**
	 * Creates a new empty document.
	 */
	public Document() {
		super();
		setTextStore(new CopyOnWriteTextStore(new GapTextStore()));
		setLineTracker(new DefaultLineTracker());
		completeInitialization();
	}

	/**
	 * Creates a new document with the given initial content.
	 *
	 * @param initialContent the document's initial content
	 */
	public Document(String initialContent) {
		super();
		setTextStore(new CopyOnWriteTextStore(new GapTextStore()));
		setLineTracker(new DefaultLineTracker());
		getStore().set(initialContent);
		getTracker().set(initialContent);
		completeInitialization();
	}

	@Override
	public boolean isLineInformationRepairNeeded(int offset, int length, String text) throws BadLocationException {
		if ((0 > offset) || (0 > length) || (offset + length > getLength()))
			throw new BadLocationException();

		return isLineInformationRepairNeeded(text) || isLineInformationRepairNeeded(get(offset, length));
	}

	/**
	 * Checks whether the line information needs to be repaired.
	 *
	 * @param text the text to check
	 * @return <code>true</code> if the line information must be repaired
	 * @since 3.4
	 */
	private boolean isLineInformationRepairNeeded(String text) {
		if (text == null)
			return false;

		int length= text.length();
		if (length == 0)
			return false;

		int rIndex= text.indexOf('\r');
		int nIndex= text.indexOf('\n');
		if (rIndex == -1 && nIndex == -1)
			return false;

		if (rIndex > 0 && rIndex < length-1 && nIndex > 1 && rIndex < length-2)
			return false;

		String defaultLD= null;
		try {
			defaultLD= getLineDelimiter(0);
		} catch (BadLocationException x) {
			return true;
		}

		if (defaultLD == null)
			return false;

		defaultLD= getDefaultLineDelimiter();

		if (defaultLD.length() == 1) {
			if (rIndex != -1 && !"\r".equals(defaultLD)) //$NON-NLS-1$
				return true;
			if (nIndex != -1 && !"\n".equals(defaultLD)) //$NON-NLS-1$
				return true;
		} else if (defaultLD.length() == 2)
			return rIndex == -1 || nIndex - rIndex != 1;

		return false;
	}


}
