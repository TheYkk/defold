package com.dynamo.cr.tileeditor;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.vecmath.Point2f;

import org.eclipse.swt.graphics.Rectangle;

import com.dynamo.cr.tileeditor.core.IGridView;
import com.dynamo.cr.tileeditor.core.Layer;
import com.dynamo.cr.tileeditor.core.Layer.Cell;

public class GridView implements IGridView {

    @Inject private IGridView.Presenter presenter;
    @Inject private GridRenderer renderer;
    @Inject private IGridEditorOutlinePage outline;

    @Override
    public void setTileSet(BufferedImage image, int tileWidth, int tileHeight,
            int tileMargin, int tileSpacing) {
        this.renderer.setTileSet(image, tileWidth, tileHeight, tileMargin, tileSpacing);
    }

    @Override
    public void setCellWidth(float cellWidth) {
        this.renderer.setCellWidth(cellWidth);
    }

    @Override
    public void setCellHeight(float cellHeight) {
        this.renderer.setCellHeight(cellHeight);
    }

    @Override
    public void setLayers(List<Layer> layers) {
        this.renderer.setLayers(layers);
        this.outline.setInput(layers, -1);
    }

    @Override
    public void setCells(int layerIndex, Map<Long, Cell> cells) {
        this.renderer.setCells(layerIndex, cells);
    }

    @Override
    public void setCell(int layerIndex, long cellIndex, Cell cell) {
        this.renderer.setCell(layerIndex, cellIndex, cell);
    }

    @Override
    public void refreshProperties() {
        this.outline.refresh();
    }

    @Override
    public void setValidModel(boolean valid) {
        // TODO Auto-generated method stub

    }

    @Override
    public void setDirty(boolean dirty) {
        // TODO Auto-generated method stub

    }

    @Override
    public Rectangle getPreviewRect() {
        return this.renderer.getViewRect();
    }

    @Override
    public void setPreview(Point2f position, float zoom) {
        this.renderer.setCamera(position, zoom);
    }

}
