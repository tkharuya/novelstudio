package android.graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
public final class BitmapFactory {
    public static final class Options {
        public boolean inJustDecodeBounds,inScaled;
        public int inSampleSize=1,outWidth=-1,outHeight=-1;
        public String outMimeType;
        public Bitmap.Config inPreferredConfig;
    }
    public static Bitmap decodeByteArray(byte[] bytes,int offset,int length,Options options) {
        try(ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes,offset,length))) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())return null;
            ImageReader reader=readers.next();
            try {
                reader.setInput(input,true,true);
                String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                options.outMimeType="image/"+("jpg".equals(format)?"jpeg":format);
                options.outWidth=reader.getWidth(0);options.outHeight=reader.getHeight(0);
                if(options.inJustDecodeBounds)return null;
                ImageReadParam param=reader.getDefaultReadParam();
                int sample=Math.max(1,options.inSampleSize);param.setSourceSubsampling(sample,sample,0,0);
                BufferedImage image=reader.read(0,param);return image==null?null:new Bitmap(image);
            } finally { reader.dispose(); }
        } catch(Exception error) { return null; }
    }
}
