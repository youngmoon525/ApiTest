//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     S h a p e S y m b o l                                      //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2021. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.ui.symbol;

import org.audiveris.omr.glyph.Shape;
import static org.audiveris.omr.glyph.Shape.*;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sig.ui.InterUIModel;

import java.awt.AlphaComposite;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

/**
 * Class <code>ShapeSymbol</code> extends the {@link BasicSymbol} with the handling of a
 * related {@link Shape}.
 * <p>
 * A ShapeSymbol thus adds several features:
 * <ul>
 * <li>It can be used for dedicated shape assignment buttons.</li>
 * <li>It can be used for Drag n' Drop operations, since it implements the {@link Transferable}
 * interface.</li>
 * <li>It can be used to <b>train</b> the glyph classifier when we don't have enough "real" glyphs
 * available.</li>
 * </ul>
 *
 * @author Hervé Bitteur
 */
public class ShapeSymbol
        extends BasicSymbol
        implements Transferable
{
    //~ Static fields/initializers -----------------------------------------------------------------

    /** The symbol meta data. */
    public static final DataFlavor DATA_FLAVOR = new DataFlavor(SymbolIcon.class, "shape-symbol");

    /** Composite used for decoration. */
    protected static final AlphaComposite decoComposite = AlphaComposite.getInstance(
            AlphaComposite.SRC_OVER,
            0.15f);

    //~ Instance fields ----------------------------------------------------------------------------
    /** Related shape. */
    protected final Shape shape;

    /** Is this a decorated symbol. (shape with additional stuff) */
    protected final boolean decorated;

    /** Decorated version if any. */
    protected ShapeSymbol decoratedSymbol;

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Create a ShapeSymbol with the provided shape and codes
     *
     * @param isIcon    true for an icon
     * @param shape     the related shape
     * @param decorated true if the symbol uses decoration around the shape
     * @param codes     the codes for MusicFont characters
     */
    public ShapeSymbol (boolean isIcon,
                        Shape shape,
                        boolean decorated,
                        int... codes)
    {
        super(isIcon, codes);
        this.shape = shape;
        this.decorated = decorated;
    }

    /**
     * Create a non decorated standard ShapeSymbol with the provided
     * shape and codes.
     *
     * @param shape the related shape
     * @param codes the codes for MusicFont characters
     */
    public ShapeSymbol (Shape shape,
                        int... codes)
    {
        this(false, shape, false, codes);
    }

    //~ Methods ------------------------------------------------------------------------------------
    //--------------------//
    // getDecoratedSymbol //
    //--------------------//
    /**
     * Report the decorated version of this ShapeSymbol.
     *
     * @return the decorated version
     */
    public ShapeSymbol getDecoratedSymbol ()
    {
        if (decorated) {
            return this;
        }

        if (decoratedSymbol == null) {
            decoratedSymbol = createDecoratedSymbol();
        }

        return decoratedSymbol;
    }

    //----------//
    // getModel //
    //----------//
    /**
     * Get inter geometry from this (dropped) symbol.
     *
     * @param font      properly scaled font
     * @param location  dropping location
     * @param alignment location alignment with respect to symbol
     * @return the data model for inter being shaped
     * @throws UnsupportedOperationException if operation is not explicitly supported by the symbol
     */
    public InterUIModel getModel (MusicFont font,
                                  Point location,
                                  Alignment alignment)
    {
        throw new UnsupportedOperationException();
    }

    //-------------//
    // updateModel //
    //-------------//
    /**
     * Tell the symbol that it can update its model with sheet informations.
     * <p>
     * This is useful when the dragged item enters a sheet view, since it can adapt itself to
     * sheet informations (such as the typical beam thickness).
     *
     * @param sheet underlying sheet
     */
    public void updateModel (Sheet sheet)
    {
        // Void, by default
    }

    //----------//
    // getShape //
    //----------//
    /**
     * Report the shape of the symbol.
     *
     * @return the shape
     */
    public Shape getShape ()
    {
        return shape;
    }

    //--------//
    // getTip //
    //--------//
    /**
     * Report the tip text to display for this symbol.
     *
     * @return the tip text
     */
    public String getTip ()
    {
        return shape.toString(); // By default, use the shape name
    }

    //-----------------//
    // getTransferData //
    //-----------------//
    @Override
    public Object getTransferData (DataFlavor flavor)
            throws UnsupportedFlavorException,
                   IOException
    {
        if (isDataFlavorSupported(flavor)) {
            return this;
        } else {
            throw new UnsupportedFlavorException(flavor);
        }
    }

    //------------------------//
    // getTransferDataFlavors //
    //------------------------//
    @Override
    public DataFlavor[] getTransferDataFlavors ()
    {
        return new DataFlavor[]{DATA_FLAVOR};
    }

    //-----------------------//
    // isDataFlavorSupported //
    //-----------------------//
    @Override
    public boolean isDataFlavorSupported (DataFlavor flavor)
    {
        return flavor == DATA_FLAVOR;
    }

    //-------------//
    // isDecorated //
    //-------------//
    /**
     * Tell whether the image represents the shape with additional
     * decorations.
     *
     * @return true if decorated
     */
    public boolean isDecorated ()
    {
        return decorated;
    }

    //------------//
    // createIcon //
    //------------//
    @Override
    protected ShapeSymbol createIcon ()
    {
        return new ShapeSymbol(true, shape, decorated, codes);
    }

    //-----------//
    // internals //
    //-----------//
    @Override
    protected String internals ()
    {
        StringBuilder sb = new StringBuilder(super.internals());

        sb.append(" ").append(shape);

        return sb.toString();
    }

    //-----------------------//
    // createDecoratedSymbol //
    //-----------------------//
    /**
     * Create symbol with proper decorations.
     * To be redefined by each subclass that does provide a specific decorated version
     *
     * @return the decorated version, which may be the same symbol if no decoration exists
     */
    protected ShapeSymbol createDecoratedSymbol ()
    {
        return this; // No decoration by default
    }

    //-------------//
    // numberCodes //
    //-------------//
    /**
     * Report the codes for a time number symbol.
     *
     * @param number integer value of the symbol (must be within [0..99] range)
     * @return corresponding codes in music font
     */
    public static int[] numberCodes (int number)
    {
        if (number < 0) {
            throw new IllegalArgumentException(number + " < 0");
        }

        if (number > 99) {
            throw new IllegalArgumentException(number + " > 99");
        }

        ShapeSymbol symbol = Symbols.getSymbol(TIME_ZERO);
        int base = symbol.codes[0];
        int[] numberCodes = (number > 9) ? new int[2] : new int[1];
        int index = 0;

        if (number > 9) {
            numberCodes[index++] = base + (number / 10);
        }

        numberCodes[index] = base + (number % 10);

        return numberCodes;
    }
}
