//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     I m a g e L o a d i n g                                    //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright (C) Brenton Partridge 2007-2008.   4
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.image;

import omr.constant.Constant;
import omr.constant.ConstantSet;

import omr.util.FileUtil;

import de.intarsys.cwt.awt.environment.CwtAwtGraphicsContext;
import de.intarsys.cwt.environment.IGraphicsContext;
import de.intarsys.pdf.content.CSContent;
import de.intarsys.pdf.parser.COSLoadException;
import de.intarsys.pdf.pd.PDDocument;
import de.intarsys.pdf.pd.PDPage;
import de.intarsys.pdf.platform.cwt.rendering.CSPlatformRenderer;
import de.intarsys.tools.locator.FileLocator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.media.jai.JAI;

/**
 * Class {@code ImageLoading} handles the loading of one or several images out of an
 * input file.
 * <p>
 * It works in two phases:<ol>
 * <li>An initial call to {@link #getLoader(Path)} tries to return a {@link Loader} instance that
 * fits the provided input file.</li>
 * <li>Then this Loader instance can be used via:<ul>
 * <li>{@link Loader#getImageCount()} to know how many images are available in the input file,</li>
 * <li>{@link Loader#getImage(int)} to return any specific image,</li>
 * <li>{@link Loader#dispose()} to finally release any resources.</li>
 * </ul>
 * </ol>
 * This class leverages several software pieces, each with its own Loader subclass:<ul>
 * <li><b>JPod</b> for PDF files. This replaces former use of GhostScript sub-process.</li>
 * <li><b>ImageIO</b> for all files except PDF.</li>
 * <li><b>JAI</b> if ImageIO failed. Note that JAI can find only one image per file.</li>
 * </ul>
 *
 * @author Hervé Bitteur
 * @author Brenton Partridge
 * @author Maxim Poliakovski
 */
public abstract class ImageLoading
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(ImageLoading.class);

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * To disallow instantiation.
     */
    private ImageLoading ()
    {
    }

    //~ Methods ------------------------------------------------------------------------------------
    //-----------//
    // getLoader //
    //-----------//
    /**
     * Build a proper loader instance dedicated to the provided image file.
     *
     * @param imgPath the provided image path
     * @return the loader instance or null if failed
     */
    public static Loader getLoader (Path imgPath)
    {
        String extension = FileUtil.getExtension(imgPath);
        Loader loader;

        if (extension.equalsIgnoreCase(".pdf")) {
            // Use JPod
            loader = getJPodLoader(imgPath);
        } else {
            // Try ImageIO
            loader = getImageIOLoader(imgPath);

            if (loader == null) {
                // Use JAI
                loader = getJaiLoader(imgPath);
            }
        }

        if (loader != null) {
            final int count = loader.getImageCount();
            logger.debug("{} sheet{} in {}", count, ((count > 1) ? "s" : ""), imgPath);
        } else {
            logger.warn("Cannot find a loader for {}", imgPath);
        }

        return loader;
    }

    //------------------//
    // getImageIOLoader //
    //------------------//
    /**
     * Try to use ImageIO.
     *
     * @param imgPath the provided input file
     * @return proper (ImageIO) loader or null if failed
     */
    private static Loader getImageIOLoader (Path imgPath)
    {
        logger.debug("getImageIOLoader {}", imgPath);

        // Input stream
        ImageInputStream stream = null;

        try {
            stream = ImageIO.createImageInputStream(imgPath.toFile());
        } catch (IOException ex) {
            logger.warn("Unable to create ImageIO stream for " + imgPath, ex);
        }

        if (stream == null) {
            logger.debug("No ImageIO input stream provider for {}", imgPath);

            return null;
        }

        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);

        if (!readers.hasNext()) {
            logger.debug("No ImageIO reader for {}", imgPath);

            return null;
        }

        try {
            ImageReader reader = readers.next();
            reader.setInput(stream, false, true);

            int imageCount = reader.getNumImages(true);

            return new ImageIOLoader(reader, imageCount);
        } catch (Exception ex) {
            logger.warn("ImageIO failed for " + imgPath, ex);

            return null;
        }
    }

    //---------------//
    // getJPodLoader //
    //---------------//
    /**
     * Try to use JPod.
     *
     * @param imgPath the provided (PDF) input file.
     * @return proper (JPod) loader or null if failed
     */
    private static Loader getJPodLoader (Path imgPath)
    {
        logger.debug("getJPodLoader {}", imgPath);

        PDDocument doc = null;

        try {
            FileLocator locator = new FileLocator(imgPath.toFile());
            doc = PDDocument.createFromLocator(locator);
        } catch (IOException ex) {
            logger.warn("Error opening pdf file " + imgPath, ex);
        } catch (COSLoadException ex) {
            logger.warn("Invalid pdf file " + imgPath, ex);
        }

        if (doc == null) {
            return null;
        }

        int imageCount = doc.getPageTree().getCount();

        return new JPodLoader(doc, imageCount);
    }

    //--------------//
    // getJaiLoader //
    //--------------//
    /**
     * Try to use JAI.
     *
     * @param imgPath the provided input file
     * @return proper (JAI) loader or null if failed
     */
    private static Loader getJaiLoader (Path imgPath)
    {
        logger.debug("getJaiLoader {}", imgPath);

        try {
            BufferedImage image = JAI.create("fileload", imgPath.toString()).getAsBufferedImage();

            if ((image != null) && (image.getWidth() > 0) && (image.getHeight() > 0)) {
                return new JaiLoader(image);
            }

            logger.debug("No image read by JAI for {}", imgPath);
        } catch (Exception ex) {
            logger.warn("JAI failed opening " + imgPath + " ", ex);
        }

        return null;
    }

    //~ Inner Interfaces ---------------------------------------------------------------------------
    //--------//
    // Loader //
    //--------//
    /**
     * A loader dedicated to an input file.
     */
    public static interface Loader
    {
        //~ Methods --------------------------------------------------------------------------------

        /**
         * Release any loader resources.
         */
        void dispose ();

        /**
         * Load the specific image.
         *
         * @param id specified image id (its index counted from 1)
         * @return the image, or null if failed
         * @throws IOException for any IO error
         */
        BufferedImage getImage (int id)
                throws IOException;

        /**
         * Report the count of images available in input file.
         *
         * @return the count of images
         */
        int getImageCount ();
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
            extends ConstantSet
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final Constant.Integer pdfResolution = new Constant.Integer(
                "DPI",
                300,
                "DPI resolution for PDF images");
    }

    //----------------//
    // AbstractLoader //
    //----------------//
    private abstract static class AbstractLoader
            implements Loader
    {
        //~ Instance fields ------------------------------------------------------------------------

        /** Count of images available in input file. */
        protected final int imageCount;

        //~ Constructors ---------------------------------------------------------------------------
        public AbstractLoader (int imageCount)
        {
            this.imageCount = imageCount;
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void dispose ()
        {
        }

        @Override
        public int getImageCount ()
        {
            return imageCount;
        }

        protected void checkId (int id)
        {
            if ((id < 1) || (id > imageCount)) {
                throw new IllegalArgumentException("Invalid image id " + id);
            }
        }
    }

    //---------------//
    // ImageIOLoader //
    //---------------//
    private static class ImageIOLoader
            extends AbstractLoader
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final ImageReader reader;

        //~ Constructors ---------------------------------------------------------------------------
        public ImageIOLoader (ImageReader reader,
                              int imageCount)
        {
            super(imageCount);
            this.reader = reader;
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void dispose ()
        {
            reader.dispose();
        }

        @Override
        public BufferedImage getImage (int id)
                throws IOException
        {
            checkId(id);

            BufferedImage img = reader.read(id - 1);

            return img;
        }
    }

    //------------//
    // JPodLoader //
    //------------//
    private static class JPodLoader
            extends AbstractLoader
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final PDDocument doc;

        //~ Constructors ---------------------------------------------------------------------------
        public JPodLoader (PDDocument doc,
                           int imageCount)
        {
            super(imageCount);
            this.doc = doc;
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public void dispose ()
        {
            try {
                doc.close();
            } catch (IOException ex) {
                logger.warn("Could not close PDDocument", ex);
            }
        }

        @Override
        public BufferedImage getImage (int id)
                throws IOException
        {
            checkId(id);

            PDPage page = doc.getPageTree().getPageAt(id - 1);
            Rectangle2D rect = page.getCropBox().toNormalizedRectangle();
            float scale = constants.pdfResolution.getValue() / 72.0f;

            BufferedImage image = new BufferedImage(
                    Math.abs((int) (rect.getWidth() * scale)),
                    Math.abs((int) (rect.getHeight() * scale)),
                    BufferedImage.TYPE_BYTE_GRAY);

            Graphics2D g2 = (Graphics2D) image.getGraphics();

            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            //g2.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
            //                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            //g2.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING,
            //   		    RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            //g2.setRenderingHint(RenderingHints.KEY_DITHERING,
            //		    RenderingHints.VALUE_DITHER_ENABLE);
            g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);

            //g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
            //	            RenderingHints.VALUE_STROKE_PURE);
            //g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            //		    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            //g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
            //		    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            IGraphicsContext gctx = new CwtAwtGraphicsContext(g2);
            AffineTransform transform = gctx.getTransform();
            transform.scale(scale, -scale);
            transform.translate(-rect.getMinX(), -rect.getMaxY());
            gctx.setTransform(transform);
            gctx.setBackgroundColor(Color.WHITE);
            gctx.fill(rect);

            CSContent content = page.getContentStream();

            if (content != null) {
                CSPlatformRenderer renderer = new CSPlatformRenderer(null, gctx);
                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_OFF);
                renderer.process(content, page.getResources());
            }

            return image;
        }
    }

    //-----------//
    // JaiLoader //
    //-----------//
    /**
     * A (degenerated) loader, since the only available image has already been cached.
     */
    private static class JaiLoader
            extends AbstractLoader
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final BufferedImage image; // The single image

        //~ Constructors ---------------------------------------------------------------------------
        public JaiLoader (BufferedImage image)
        {
            super(1); // JAI can return just one image
            this.image = image;
        }

        //~ Methods --------------------------------------------------------------------------------
        @Override
        public BufferedImage getImage (int id)
                throws IOException
        {
            checkId(id);

            return image;
        }
    }
}