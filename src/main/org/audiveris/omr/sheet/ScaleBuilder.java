//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                    S c a l e B u i l d e r                                     //
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
package org.audiveris.omr.sheet;

import static org.audiveris.omr.WellKnowns.LINE_SEPARATOR;
import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.math.HiLoPeakFinder;
import org.audiveris.omr.math.HiLoPeakFinder.Quorum;
import org.audiveris.omr.math.IntegerFunction;
import org.audiveris.omr.math.Range;
import org.audiveris.omr.run.Run;
import org.audiveris.omr.run.RunTable;
import org.audiveris.omr.sheet.Scale.BeamScale;
import org.audiveris.omr.sheet.Scale.InterlineScale;
import org.audiveris.omr.sheet.Scale.LineScale;
import org.audiveris.omr.step.StepException;
import org.audiveris.omr.util.ChartPlotter;
import org.audiveris.omr.util.Navigable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.util.Iterator;
import java.util.List;

/**
 * Class <code>ScaleBuilder</code> computes the global scale of a given sheet by processing
 * the image vertical runs.
 * <p>
 * Adding the most frequent black run length to the most frequent white run length,
 * would give the average interline value.
 * Instead of a posteriori addition, we analyze the total length of two combo runs in sequence.
 * <p>
 * A second black peak usually gives the average beam thickness.
 * And similarly, a second combo peak may indicate a series of staves with a different interline
 * than the main series.
 * <p>
 * Internally, additional validity checks are performed:
 * <ol>
 * <li>If we cannot retrieve black peak, we decide that the sheet does not contain significant
 * lines.</li>
 * <li>If we cannot retrieve combo peak, we decide that the sheet does not contain regularly spaced
 * staff lines.</li>
 * <li>Method {@link #checkResolution} looks at combo peak.
 * If the main interline value is below a certain threshold (see constants.minInterline), then we
 * suspect that the picture is not a music sheet (it may rather be an image, a page of text, etc).
 * </li>
 * </ol>
 * <p>
 * If we have doubts about the page at hand and if this page is part of a multi-page score, we
 * propose to simply discard this sheet. In batch, the page is discarded without asking for
 * confirmation.
 *
 * @see Scale
 * @author Hervé Bitteur
 */
public class ScaleBuilder
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(ScaleBuilder.class);

    //~ Instance fields ----------------------------------------------------------------------------
    /** Related sheet. */
    @Navigable(false)
    private final Sheet sheet;

    /** Whole vertical run table. */
    private RunTable binary;

    /** Keeper of vertical run length histograms, for foreground & background. */
    private HistoKeeper histoKeeper;

    /** Most frequent length of vertical black runs found. */
    private Range blackPeak;

    /** Most frequent length of vertical combined runs found. */
    private Range comboPeak;

    /** Second frequent length of vertical combined runs found, if any. */
    private Range comboPeak2;

    /** Main beam thickness found, if any. */
    private Integer beamKey;

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Constructor to enable scale computation on a given sheet.
     *
     * @param sheet the sheet at hand
     */
    public ScaleBuilder (Sheet sheet)
    {
        this.sheet = sheet;
    }

    //~ Methods ------------------------------------------------------------------------------------
    //--------------//
    // displayChart //
    //--------------//
    /**
     * Display the scale histograms.
     */
    public void displayChart ()
    {
        if (histoKeeper == null) {
            try {
                doRetrieveScale(true); // Dummy retrieval
            } catch (StepException ignored) {
            }
        }

        if (histoKeeper != null) {
            histoKeeper.writePlot();
        }
    }

    //---------------//
    // retrieveScale //
    //---------------//
    /**
     * Retrieve scale information on sheet.
     *
     * @return scale information
     * @throws StepException raised if some mandatory information cannot be retrieved.
     *                       For example if no staves are found.
     */
    public Scale retrieveScale ()
            throws StepException
    {
        return doRetrieveScale(false);
    }

    //-----------------//
    // checkResolution //
    //-----------------//
    /**
     * Check global interline value, to detect pictures with too low
     * resolution or pictures which do not represent music staves.
     *
     * @throws StepException if processing must stop on this sheet
     */
    private void checkResolution ()
            throws StepException
    {
        int interline = comboPeak.main;

        if (interline == 0) {
            sheet.getStub().decideOnRemoval(
                    sheet.getId() + LINE_SEPARATOR
                            + "Interline value is zero."
                            + LINE_SEPARATOR
                            + "This sheet does not seem to contain staff lines.",
                    false);
        } else if (interline < constants.minInterline.getValue()) {
            sheet.getStub().decideOnRemoval(
                    sheet.getId() + LINE_SEPARATOR
                            + "With an interline value of "
                            + interline
                            + " pixels,"
                            + LINE_SEPARATOR
                            + "either this sheet contains no staves,"
                            + LINE_SEPARATOR
                            + "or the picture resolution is too low (try 300 DPI).",
                    false);
        } else if (interline > constants.maxInterline.getValue()) {
            sheet.getStub().decideOnRemoval(
                    sheet.getId() + LINE_SEPARATOR
                            + "Too large interline value: "
                            + interline
                            + " pixels"
                            + LINE_SEPARATOR
                            + "This sheet does not seem to contain staff lines.",
                    false);
        }
    }

    //-------------//
    // computeBeam //
    //-------------//
    /**
     * Report the beam scale information for the sheet.
     * <p>
     * We try to retrieve a beam key in black histogram, otherwise we extrapolate a probable beam
     * height based on minimum and maximum height values.
     *
     * @param verbose true for printing out computation details
     * @return the beam scale
     */
    private BeamScale computeBeam (boolean verbose)
    {
        // Scale data?
        if (comboPeak == null) {
            logger.warn("No global scale information available");

            return null;
        }

        // Beam guess
        final int largerInterline = getLargerInterline();
        final int minHeight = Math.max(
                blackPeak.max,
                (int) Math.rint(constants.beamMinFraction.getValue() * largerInterline));
        final int maxHeight = Math.max(
                largerInterline - blackPeak.main,
                (int) Math.rint(constants.beamMaxFraction.getValue() * largerInterline));
        final double beamRatio = constants.beamRangeRatio.getValue();
        final int guess = (int) Math.rint(minHeight + ((maxHeight - minHeight) * beamRatio));

        if (verbose) {
            logger.info(String.format("Beam  guessed height: %2d -- %.2f of [%d..%d] range",
                                      guess, beamRatio, minHeight, maxHeight));
        }

        // Beam measurement
        final int quorum = histoKeeper.getBeamQuorum();

        // NOTA: At this point in time, blackFinder.findPeaks() has already been used for staff line.
        // Setting quorum for blackFinder here is just for the potential plot
        histoKeeper.blackFinder.setQuorum(new Quorum(quorum, minHeight, maxHeight));

        List<Integer> peaks = histoKeeper.blackFunction.getLocalMaxima(minHeight, maxHeight);

        if (!peaks.isEmpty()) {
            final int peak = peaks.get(0);
            final double quorumRatio = (double) histoKeeper.blackFunction.getValue(peak) / quorum;
            final double rangeRatio = (double) (peak - minHeight) / (maxHeight - minHeight);

            if (verbose) {
                logger.info(String.format("Beam measured height: %2d -- %.2f"
                                                  + " of [%d..%d] range at %d%% of needed quorum",
                                          peak, rangeRatio, minHeight, maxHeight,
                                          (int) Math.rint(quorumRatio * 100)));
            }

            // Quorum reached or measured value close to guess?
            final int diff = Math.abs(peak - guess);

            if ((quorumRatio >= 1.0) || (diff <= constants.beamMaxDiff.getValue())) {
                beamKey = peak;

                if (verbose) {
                    logger.info("Beam height: {}", beamKey);
                }
            }
        }

        if (beamKey != null) {
            return new BeamScale(beamKey, false);
        } else {
            // Beam extrapolation from height possible range
            if (verbose) {
                logger.warn("No reliable beam height found, guessed value: {}", guess);
            }

            return new BeamScale(guess, true);
        }
    }

    //------------------//
    // computeInterline //
    //------------------//
    private InterlineScale computeInterline ()
    {
        if ((comboPeak2 == null) || (comboPeak2.main < comboPeak.main)) {
            return new InterlineScale(comboPeak);
        } else {
            return new InterlineScale(comboPeak2);
        }
    }

    //-----------------------//
    // computeSmallInterline //
    //-----------------------//
    private InterlineScale computeSmallInterline ()
    {
        if (comboPeak2 == null) {
            return null;
        }

        if (comboPeak2.main < comboPeak.main) {
            return new InterlineScale(comboPeak2);
        } else {
            return new InterlineScale(comboPeak);
        }
    }

    //-----------------//
    // doRetrieveScale //
    //-----------------//
    /**
     * Retrieve the global scale counts by processing the provided picture runs,
     * make decisions about the validity of current picture as a music page and store
     * the results as a {@link Scale} instance in the related sheet.
     *
     * @param dummy true for dummy retrieval (just for the chart)
     * @return scale data for the sheet
     * @throws StepException if processing must stop for this sheet.
     */
    private Scale doRetrieveScale (boolean dummy)
            throws StepException
    {
        binary = sheet.getPicture().getTable(Picture.TableKey.BINARY);
        histoKeeper = new HistoKeeper();

        histoKeeper.buildBlacks();
        histoKeeper.retrieveLinePeak(); // -> blackPeak (or StepException thrown)

        histoKeeper.buildCombos();
        histoKeeper.retrieveInterlinePeaks(); // -> comboPeak (or StepException thrown), comboPeak2?

        if (dummy) {
            computeBeam(false); // Just for the chart

            return null;
        }

        // Check we have acceptable resolution.  If not, throw StepException
        checkResolution();

        // Here, we keep going on with scale data
        InterlineScale smallInterlineScale = computeSmallInterline();
        Scale smallScale = (smallInterlineScale == null) ? null
                : new Scale(smallInterlineScale, null, null, null);

        // Respect user-assigned scale info, if any
        final Scale scl = sheet.getScale();
        final Scale scale;

        if (scl != null) {
            scale = new Scale(
                    (scl.getInterlineScale() != null) ? scl.getInterlineScale() : computeInterline(),
                    (scl.getLineScale() != null) ? scl.getLineScale() : new LineScale(blackPeak),
                    (scl.getBeamScale() != null) ? scl.getBeamScale() : computeBeam(true),
                    (scl.getSmallScale() != null) ? scl.getSmallScale() : smallScale);

            if (scl.getStemScale() != null) {
                scale.setStemScale(scl.getStemScale());
            }
        } else {
            scale = new Scale(
                    computeInterline(),
                    new LineScale(blackPeak),
                    computeBeam(true),
                    smallScale);
        }

        return scale;
    }

    //--------------------//
    // getLargerInterline //
    //--------------------//
    /**
     * Report the larger of comboPeak.main (and comboPeak2.main if any)
     *
     * @return (larger) main interline
     */
    private int getLargerInterline ()
    {
        int mainCombo = comboPeak.main;

        if (comboPeak2 != null) {
            mainCombo = Math.max(mainCombo, comboPeak2.main);
        }

        return mainCombo;
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //-----------//
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {

        private final Constant.Integer minInterline = new Constant.Integer(
                "Pixels",
                11,
                "Minimum interline value (in pixels)");

        private final Constant.Integer maxInterline = new Constant.Integer(
                "Pixels",
                100,
                "Maximum interline value (in pixels)");

        private final Constant.Ratio minGainRatio = new Constant.Ratio(
                0.03,
                "Minimum ratio of peak runs for peak extension");

        private final Constant.Ratio minDerivativeRatio = new Constant.Ratio(
                0.025,
                "Minimum ratio of total runs for derivative acceptance");

        private final Constant.Ratio minBlackRatio = new Constant.Ratio(
                0.001,
                "Minimum ratio of foreground pixels in image");

        private final Constant.Ratio maxSecondRatio = new Constant.Ratio(
                1.9, // 2.0 led to a false second peak in merged grand staff
                "Maximum ratio between second and first combined peak");

        private final Constant.Ratio beamMinFraction = new Constant.Ratio(
                0.275,
                "Minimum ratio between beam height and interline");

        private final Constant.Ratio beamMaxFraction = new Constant.Ratio(
                1.0,
                "Maximum ratio between beam height and interline");

        private final Constant.Ratio beamMinCountRatio = new Constant.Ratio(
                0.01,
                "Minimum ratio of runs for beam height measurement (quorum)");

        private final Constant.Ratio beamRangeRatio = new Constant.Ratio(
                0.5,
                "Ratio of beam possible height range for guess");

        private final Constant.Integer beamMaxDiff = new Constant.Integer(
                "pixels",
                2,
                "Maximum difference between beam guess and measurement");
    }

    //-------------//
    // HistoKeeper //
    //-------------//
    /**
     * This class handles the histograms of vertical lengths for black runs (foreground)
     * and combo runs (black + white and white + black).
     * It retrieves the various peaks and is able to display a chart on the related populations.
     */
    private class HistoKeeper
    {

        // Upper bounds for run lengths (assuming sheet height >= staff height)
        final int maxBlack;

        final int maxWhite;

        final IntegerFunction blackFunction;

        final HiLoPeakFinder blackFinder;

        final IntegerFunction comboFunction;

        final HiLoPeakFinder comboFinder;

        HistoKeeper ()
        {
            // We assume at least one staff in sheet, hence some maximum values for relevant white
            // and black runs.
            maxBlack = binary.getHeight() / 16;
            maxWhite = binary.getHeight() / 4;
            logger.debug(
                    "maxBlack:{}, maxWhite:{}, maxCombo:{}",
                    maxBlack,
                    maxWhite,
                    maxBlack + maxWhite);

            // Allocate histograms
            blackFunction = new IntegerFunction(0, maxBlack);
            blackFinder = new HiLoPeakFinder("black", blackFunction);

            comboFunction = new IntegerFunction(0, maxBlack + maxWhite);
            comboFinder = new HiLoPeakFinder("combo", comboFunction);
        }

        //-------------//
        // buildBlacks //
        //-------------//
        /**
         * Populate the black histogram.
         */
        public void buildBlacks ()
        {
            for (int x = 0, width = binary.getWidth(); x < width; x++) {
                for (Iterator<Run> it = binary.iterator(x); it.hasNext();) {
                    int black = it.next().getLength();

                    if (black <= maxBlack) {
                        blackFunction.addValue(black, 1);
                    }
                }
            }

            if (logger.isDebugEnabled()) {
                blackFunction.print(System.out);
            }
        }

        //-------------//
        // buildCombos //
        //-------------//
        /**
         * Populate the combo histogram.
         * <p>
         * A combo is the total length of blackRun + next whiteRun or whiteRun + next blackRun,
         * provided that blackRun length and whiteRun length are in relevant ranges.
         * <p>
         * NOTA: Roughly, all counts are doubled by the way we count the combos.
         * This has no real impact, because integral value (which is used as the base for quorum)
         * and derivative values are also doubled.
         */
        public void buildCombos ()
        {
            for (int x = 0, width = binary.getWidth(); x < width; x++) {
                int yLast = 0; // Ordinate of first pixel not yet processed
                int lastBlack = 0; // Length of last valid black run

                for (Iterator<Run> it = binary.iterator(x); it.hasNext();) {
                    Run run = it.next();
                    final int y = run.getStart();
                    final int black = run.getLength();

                    if ((black < blackPeak.min) || (black > blackPeak.max)) {
                        lastBlack = 0;
                    } else {
                        if (y > yLast) {
                            // Process the white run before this black run
                            int white = y - yLast;

                            // A white run between valid black runs?: B1, W, B2
                            // Combo 1 is defined as B1 + W, that is [-----]
                            // Combo 2 is defined as W + B2, that is     [-----]
                            // combo1 + combo2 = 2 * (1/2 * B1 + W + 1/2 * B2) = 2 * combo
                            if ((white <= maxWhite) && (lastBlack != 0)) {
                                comboFunction.addValue(lastBlack + white, 1); // B1 + W
                                comboFunction.addValue(white + black, 1); // W + B2
                            }
                        }

                        lastBlack = black;
                    }

                    yLast = y + black;
                }
            }

            if (logger.isDebugEnabled()) {
                comboFunction.print(System.out);
            }
        }

        //
        //        //-----------------//
        //        // retrieveBeamKey //
        //        //-----------------//
        //        /**
        //         * Try to retrieve a suitable beam thickness value.
        //         * <p>
        //         * Take most frequent black local max for which key (beam thickness) is larger than a
        //         * minimum fraction of interline and smaller than main white gap between (large) staff
        //         * lines.
        //         *
        //         * @param minHeight start value for height range
        //         * @param maxHeight stop value for height range
        //         */
        //        public Integer retrieveBeamKey (int minHeight,
        //                                        int maxHeight)
        //        {
        //            List<Integer> localMaxima = blackFunction.getLocalMaxima(minHeight, maxHeight);
        //
        //            // Quorum on height histo
        //            final int totalArea = blackFunction.getArea();
        //            final double ratio = constants.minBeamCountRatio.getValue();
        //            final int quorum = (int) Math.rint(totalArea * ratio);
        //            logger.info("Beam minHeight:{} maxHeight:{} quorum:{}", minHeight, maxHeight, quorum);
        //            blackFinder.setQuorum(new Quorum(quorum, minHeight, maxHeight));
        //
        //            for (int local : localMaxima) {
        //                if ((local >= minHeight) && (local <= maxHeight)) {
        //                    if (blackFunction.getValue(local) >= quorum) {
        //                        return local;
        //                    }
        //
        //                    break;
        //                }
        //            }
        //
        //            return null;
        //        }
        //
        //---------------//
        // getBeamQuorum //
        //---------------//
        /**
         * Report the quorum needed to validate beam height measurement.
         *
         * @return beam quorum, specified in cumulated runs count
         */
        public int getBeamQuorum ()
        {
            // Quorum on height histo
            final int totalArea = blackFunction.getArea();
            final double ratio = constants.beamMinCountRatio.getValue();

            return (int) Math.rint(totalArea * ratio);
        }

        //------------------------//
        // retrieveInterlinePeaks //
        //------------------------//
        /**
         * Retrieve combo peak for interline (and interline2 if any).
         * <p>
         * FYI: If comboPeak and comboPeak2 get modified, they will appear as such in scale info,
         * but the chart will still display the (original) peaks found.
         */
        public void retrieveInterlinePeaks ()
                throws StepException
        {
            // Combo peak(s)
            final int area = comboFunction.getArea();
            List<Range> comboPeaks = comboFinder.findPeaks(
                    1,
                    (int) Math.rint(area * constants.minDerivativeRatio.getValue()),
                    constants.minGainRatio.getValue());

            if (comboPeaks.isEmpty()) {
                sheet.getStub().invalidate();
                throw new StepException("No regularly spaced lines found");
            }

            // Primary combo peak
            comboPeak = comboPeaks.get(0);
            logger.debug("comboPeak: {}", comboPeak);
            comboPeaks = comboPeaks.subList(1, comboPeaks.size());

            // Filter other peaks with respect to the primary one
            // They cannot be too far or too close
            for (Iterator<Range> it = comboPeaks.iterator(); it.hasNext();) {
                final Range p = it.next();
                final int min = Math.min(p.main, comboPeak.main);
                final int max = Math.max(p.main, comboPeak.main);

                if ((max / min) > constants.maxSecondRatio.getValue()) {
                    logger.info("Other combo peak too different {}, discarded", p);
                    it.remove();
                } else if (Math.abs(p.main - comboPeak.main) < blackPeak.main) {
                    logger.info("Merging two close combo peaks {} & {}", comboPeak, p);
                    comboPeak = new Range(min, (p.main + comboPeak.main) / 2, max);
                    it.remove();
                }
            }

            // Secondary combo peak?
            if (!comboPeaks.isEmpty()) {
                comboPeak2 = comboPeaks.get(0);
            }
        }

        //------------------//
        // retrieveLinePeak //
        //------------------//
        /**
         * Retrieve black peak for line thickness.
         */
        public void retrieveLinePeak ()
                throws StepException
        {
            // Check we have enough foreground material. If not, throw StepException
            checkBlack();

            // Black peaks
            final int area = blackFunction.getArea();
            final List<Range> blackPeaks = blackFinder.findPeaks(
                    1,
                    (int) Math.rint(area * constants.minDerivativeRatio.getValue()),
                    constants.minGainRatio.getValue());

            if (blackPeaks.isEmpty()) {
                sheet.getStub().invalidate();
                throw new StepException("No significant black lines found");
            }

            // If several black peaks were found, pick up the one with highest function value
            int bestCount = 0;

            for (Range peak : blackPeaks) {
                final int count = blackFunction.getValue(peak.main);

                if ((blackPeak == null) || (bestCount < count)) {
                    blackPeak = peak;
                    bestCount = count;
                }
            }

            logger.debug("blackPeak: {}", blackPeak);
        }

        //-----------//
        // writePlot //
        //-----------//
        public void writePlot ()
        {
            // Determine a suitable upper value for x
            int xMax;

            if (blackPeak != null) {
                xMax = blackPeak.max;
            } else {
                xMax = 30;
            }

            if (comboPeak != null) {
                xMax = Math.max(xMax, comboPeak.max);
            }

            if (comboPeak2 != null) {
                xMax = Math.max(xMax, comboPeak2.max);
            }

            xMax = Math.min(sheet.getWidth() - 1, (xMax * 5) / 2); // Add some margin

            Scale scale = sheet.getScale();
            String xLabel = "Runs lengths - " + ((scale != null) ? scale.toString(false)
                    : "NO_SCALE");

            try {
                final String title = sheet.getId() + " " + blackFinder.name;
                final String yLabel = "Runs counts - total:" + blackFunction.getArea()
                                              + " - Beam quorum:" + getBeamQuorum();
                ChartPlotter plotter = new ChartPlotter(title, xLabel, yLabel);
                blackFinder.plot(plotter, true, 0, xMax).display(new Point(20, 20));
            } catch (Throwable ex) {
                logger.warn("Error in plotting black", ex);
            }

            try {
                final String title = sheet.getId() + " " + comboFinder.name;
                final String yLabel = "Runs counts";
                ChartPlotter plotter = new ChartPlotter(title, xLabel, yLabel);
                comboFinder.plot(plotter, true, 0, xMax).display(new Point(80, 80));
            } catch (Throwable ex) {
                logger.warn("Error in plotting combo", ex);
            }
        }

        //------------//
        // checkBlack //
        //------------//
        /**
         * Check we have a significant number of black pixels WRT image size,
         * otherwise the sheet is mostly blank and contains no music.
         *
         * @throws StepException if processing must stop on this sheet
         */
        private void checkBlack ()
                throws StepException
        {
            final int blackCount = getBlackCount();
            final int size = binary.getWidth() * binary.getHeight();
            final double blackRatio = (double) blackCount / size;
            logger.debug("blackRatio: {}", blackRatio);

            if (blackRatio < constants.minBlackRatio.getValue()) {
                sheet.getStub().decideOnRemoval(
                        sheet.getId() + LINE_SEPARATOR
                                + "Too few black pixels: "
                                + String.format("%.4f%%", 100 * blackRatio)
                                + LINE_SEPARATOR
                                + "This sheet is almost blank.",
                        false);
            }
        }

        //---------------//
        // getBlackCount //
        //---------------//
        /**
         * Compute the total number of black pixels.
         * <p>
         * We use the fact that in 'blackHisto', x represents a black run length and y the number
         * of occurrences of this run length in the image.
         *
         * @return the count of black pixels
         */
        private int getBlackCount ()
        {
            int total = 0;
            int xMin = blackFunction.getXMin();

            for (int i = xMin; i <= blackFunction.getXMax(); i++) {
                total += ((i - xMin) * blackFunction.getValue(i));
            }

            return total;
        }
    }
}
