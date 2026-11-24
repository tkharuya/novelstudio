package android.graphics;
import java.awt.geom.AffineTransform;
public final class Matrix {
    final AffineTransform transform = new AffineTransform();
    public void setScale(float x, float y) { transform.setToScale(x,y); }
    public void setRotate(float degrees) { transform.setToRotation(Math.toRadians(degrees)); }
    public boolean postScale(float x,float y) { transform.preConcatenate(AffineTransform.getScaleInstance(x,y)); return true; }
}
