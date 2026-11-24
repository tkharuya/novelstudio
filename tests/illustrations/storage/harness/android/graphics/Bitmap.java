package android.graphics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import javax.imageio.ImageIO;
public final class Bitmap {
    public enum Config { ARGB_8888 }
    public enum CompressFormat { PNG }
    private final BufferedImage image;
    Bitmap(BufferedImage image) { this.image=image; }
    public int getWidth() { return image.getWidth(); }
    public int getHeight() { return image.getHeight(); }
    public boolean compress(CompressFormat format,int quality,OutputStream output) {
        try { return ImageIO.write(image,"png",output); }
        catch(IOException error) { return false; }
    }
    public void recycle() { image.flush(); }
    public static Bitmap createBitmap(Bitmap source,int x,int y,int width,int height,Matrix matrix,boolean filter) {
        Rectangle2D bounds=matrix.transform.createTransformedShape(new Rectangle2D.Double(0,0,width,height)).getBounds2D();
        BufferedImage out=new BufferedImage((int)Math.round(bounds.getWidth()),(int)Math.round(bounds.getHeight()),BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics=out.createGraphics();
        AffineTransform transform=AffineTransform.getTranslateInstance(-bounds.getMinX(),-bounds.getMinY());
        transform.concatenate(matrix.transform);graphics.setTransform(transform);
        graphics.drawImage(source.image.getSubimage(x,y,width,height),0,0,null);graphics.dispose();return new Bitmap(out);
    }
}
