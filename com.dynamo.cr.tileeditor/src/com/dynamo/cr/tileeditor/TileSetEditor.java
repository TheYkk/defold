package com.dynamo.cr.tileeditor;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.vecmath.Vector3f;

import org.eclipse.core.commands.operations.IOperationApprover;
import org.eclipse.core.commands.operations.IOperationHistory;
import org.eclipse.core.commands.operations.UndoContext;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.operations.LinearUndoViolationUserApprover;
import org.eclipse.ui.operations.RedoActionHandler;
import org.eclipse.ui.operations.UndoActionHandler;
import org.eclipse.ui.part.EditorPart;
import org.eclipse.ui.part.IPageSite;
import org.eclipse.ui.progress.IProgressService;
import org.eclipse.ui.statushandlers.StatusManager;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;
import org.eclipse.ui.views.properties.IPropertySheetPage;

import com.dynamo.cr.editor.core.EditorUtil;
import com.dynamo.cr.properties.FormPropertySheetPage;
import com.dynamo.cr.tileeditor.core.ITileSetView;
import com.dynamo.cr.tileeditor.core.TileSetModel;
import com.dynamo.cr.tileeditor.core.TileSetPresenter;

public class TileSetEditor extends EditorPart implements
ITileSetView,
IResourceChangeListener {

    private IContainer contentRoot;
    private IOperationHistory history;
    private UndoContext undoContext;
    private TileSetPresenter presenter;
    private TileSetEditorOutlinePage outlinePage;
    private FormPropertySheetPage propertySheetPage;
    private boolean dirty = false;
    private boolean refreshPropertiesPosted = false;
    // avoids reloading while saving
    private boolean inSave = false;
    private TileSetRenderer renderer;

    // EditorPart

    @Override
    public void init(IEditorSite site, IEditorInput input)
            throws PartInitException {
        ResourcesPlugin.getWorkspace().addResourceChangeListener(this);

        setSite(site);
        setInput(input);
        setPartName(input.getName());

        IFileEditorInput fileEditorInput = (IFileEditorInput) input;
        IFile file = fileEditorInput.getFile();
        this.contentRoot = EditorUtil.findContentRoot(file);
        if (this.contentRoot == null) {
            throw new PartInitException(
                    "Unable to locate content root for project");
        }

        this.undoContext = new UndoContext();
        this.history = PlatformUI.getWorkbench().getOperationSupport()
                .getOperationHistory();
        this.history.setLimit(this.undoContext, 100);

        IOperationApprover approver = new LinearUndoViolationUserApprover(
                this.undoContext, this);
        this.history.addOperationApprover(approver);

        final TileSetModel model = new TileSetModel(this.contentRoot, this.history, this.undoContext);
        this.presenter = new TileSetPresenter(model, this);

        final String undoId = ActionFactory.UNDO.getId();
        final UndoActionHandler undoHandler = new UndoActionHandler(this.getEditorSite(), undoContext);
        final String redoId = ActionFactory.REDO.getId();
        final RedoActionHandler redoHandler = new RedoActionHandler(this.getEditorSite(), undoContext);

        IActionBars actionBars = site.getActionBars();
        actionBars.setGlobalActionHandler(undoId, undoHandler);
        actionBars.setGlobalActionHandler(redoId, redoHandler);

        this.outlinePage = new TileSetEditorOutlinePage(this.presenter) {
            @Override
            public void init(IPageSite pageSite) {
                super.init(pageSite);
                IActionBars actionBars = pageSite.getActionBars();
                actionBars.setGlobalActionHandler(undoId, undoHandler);
                actionBars.setGlobalActionHandler(redoId, redoHandler);
            }
        };
        this.propertySheetPage = new FormPropertySheetPage() {
            @Override
            public void createControl(Composite parent) {
                super.createControl(parent);
                getViewer().setInput(new Object[] {model});
            }

            @Override
            public void setActionBars(IActionBars actionBars) {
                super.setActionBars(actionBars);
                actionBars.setGlobalActionHandler(undoId, undoHandler);
                actionBars.setGlobalActionHandler(redoId, redoHandler);
            }

            @Override
            public void selectionChanged(IWorkbenchPart part,
                    ISelection selection) {
                // Ignore selections for this property view
            }
        };

        IProgressService service = PlatformUI.getWorkbench()
                .getProgressService();
        TileSetLoader loader = new TileSetLoader(file, this.presenter);
        try {
            service.runInUI(service, loader, null);
            if (loader.exception != null) {
                throw new PartInitException(loader.exception.getMessage(),
                        loader.exception);
            }
        } catch (Throwable e) {
            throw new PartInitException(e.getMessage(), e);
        }
    }

    @Override
    public void dispose() {
        super.dispose();

        this.presenter.dispose();
        if (this.renderer != null) {
            this.renderer.dispose();
        }
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
    }

    @Override
    public void createPartControl(Composite parent) {

        this.renderer = new TileSetRenderer(this.presenter, parent);

        // This makes sure the context will be active while this component is
        IContextService contextService = (IContextService) getSite()
                .getService(IContextService.class);
        contextService.activateContext(Activator.CONTEXT_ID);

        // Set the outline as selection provider
        getSite().setSelectionProvider(this.outlinePage);

        this.presenter.refresh();
    }

    public TileSetPresenter getPresenter() {
        return this.presenter;
    }

    @Override
    public boolean isDirty() {
        return this.dirty;
    }

    @Override
    public void doSave(IProgressMonitor monitor) {
        this.inSave = true;
        try {
            IFileEditorInput input = (IFileEditorInput) getEditorInput();
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            this.presenter.save(stream, monitor);
            input.getFile().setContents(
                    new ByteArrayInputStream(stream.toByteArray()), false,
                    true, monitor);
        } catch (Throwable e) {
            Status status = new Status(IStatus.ERROR, Activator.PLUGIN_ID, 0,
                    e.getMessage(), null);
            StatusManager.getManager().handle(status, StatusManager.LOG | StatusManager.SHOW);
        } finally {
            this.inSave = false;
        }

    }

    @Override
    public void doSaveAs() {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean isSaveAsAllowed() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void setFocus() {
        // TODO Auto-generated method stub

    }

    // IResourceChangeListener

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        if (this.inSave)
            return;

    }

    // ITileSetView

    @Override
    public void refreshProperties() {
        postRefreshProperties();
    }

    @Override
    public void setCollisionGroups(List<String> collisionGroups, List<Color> colors, String[] selectedCollisionGroups) {
        this.outlinePage.setInput(collisionGroups, colors, selectedCollisionGroups);
    }

    @Override
    public void setTiles(BufferedImage image, float[] v, int[] hullIndices,
            int[] hullCounts, Color[] hullColors, Vector3f hullScale) {
        if (this.renderer != null) {
            this.renderer.setTiles(image, v, hullIndices, hullCounts, hullColors, hullScale);
        }
    }

    @Override
    public void clearTiles() {
        if (this.renderer != null) {
            this.renderer.clearTiles();
        }
    }

    @Override
    public void setTileHullColor(int tileIndex, Color color) {
        if (this.renderer != null) {
            this.renderer.setTileHullColor(tileIndex, color);
        }
    }

    @Override
    public void setDirty(boolean dirty) {
        this.dirty = dirty;
        firePropertyChange(PROP_DIRTY);
    }

    @Override
    public Object getAdapter(@SuppressWarnings("rawtypes") Class adapter) {
        if (adapter == IPropertySheetPage.class) {
            return this.propertySheetPage;
        } else if (adapter == IContentOutlinePage.class) {
            return this.outlinePage;
        } else {
            return super.getAdapter(adapter);
        }
    }

    private void postRefreshProperties() {
        if (!refreshPropertiesPosted) {
            refreshPropertiesPosted = true;

            Display.getDefault().timerExec(100, new Runnable() {

                @Override
                public void run() {
                    refreshPropertiesPosted = false;
                    propertySheetPage.refresh();
                }
            });
        }
    }

}
