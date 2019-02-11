
package sample;
public class SupportVector {

    /** The input vector */
    final double[] x;
    /** The target class: either +1 or -1 */
    final int y;
    /** The Lagrange multiplier for this example */
    double alpha = 0;
    /** Is the Lagrange multiplier bound? */
    //transient boolean bound = true;//HOM
    boolean bound = true;
    
    public SupportVector(double x1, double x2, int y) {
        this(new double[] {x1, x2}, y);
    }

    public SupportVector(double x1, double x2, double x3, double x4, int y) {
        this(new double[] {x1, x2, x3, x4}, y);
    }

    public SupportVector(double[] x, int y) {
        this.x = x;
        if (y >= 1) {
            this.y = 1;
        } else {
            this.y = -1;
        }
    }

//    public double sign() {
//        return y == 0 ? -1.0 : 1.0;
//    }
}