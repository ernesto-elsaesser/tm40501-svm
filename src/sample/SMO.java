/*
 * Copyright (C) 2010-2011 David A Roberts <d@vidr.cc>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package sample;

import java.util.*;
import java.util.Map.Entry;

/**
 * An implementation of the Sequential Minimal Optimization (SMO) algorithm
 * described by J C Platt (1998) in
 * <a href="http://research.microsoft.com/pubs/68391/smo-book.pdf">
 * Fast Training of Support Vector Machines
 * using Sequential Minimal Optimization</a>.
 *
 * @author  David A Roberts
 */
class SMO {
    private static final Random random = new Random();
    private SVM svm;
    private Map<SupportVector,Double> errorCache = new HashMap<>();

    public SMO(SVM svm) {
        this.svm = svm;
    }

    public void train() {
        int numChanged = 0;
        boolean examineAll = true; // examine entire training set initially
        while(numChanged > 0 || examineAll) {
            numChanged = 0;
            for(SupportVector v : svm.vectors)
                if((examineAll || !v.bound) && examineExample(v))
                    numChanged++;
            if(examineAll)
                // only examine non-bound examples in next pass
                examineAll = false;
            else if(numChanged == 0)
                // all of the non-bound examples satisfy the KKT conditions,
                // so examine the entire training set again
                examineAll = true;
        }
    }

    private boolean examineExample(SupportVector v) {
        if(satisfiesKKTConditions(v)) // not eligible for optimisation
            return false;
        // choose a vector with the second choice heuristic
        if(!errorCache.isEmpty() && takeStep(secondChoice(error(v)), v))
            return true;
        // the heuristic did not make positive progress,
        // so try all non-bound examples
        final int n = svm.vectors.size();
        final int pos = random.nextInt(n); // iterate from random position
        for(SupportVector v1 : svm.vectors.subList(pos, n))
            if(!v1.bound && takeStep(v1, v))
                return true;
        for(SupportVector v1 : svm.vectors.subList(0, pos))
            if(!v1.bound && takeStep(v1, v))
                return true;
        // positive progress was not made, so try entire training set
        for(SupportVector v1 : svm.vectors.subList(pos, n))
            if(v1.bound && takeStep(v1, v))
                return true;
        for(SupportVector v1 : svm.vectors.subList(0, pos))
            if(v1.bound && takeStep(v1, v))
                return true;
        // no adequate second example exists, so pick another first example
        return false;
    }

    private double error(SupportVector v) {
        if(errorCache.containsKey(v))
            return errorCache.get(v);
        return svm.output(v.x) - v.y;
    }

    private boolean satisfiesKKTConditions(SupportVector v) {
        final double r = error(v) * v.y; // (u-y)*y = y*u-1
        // (r >= 0 or alpha >= C) and (r <= 0 or alpha <= 0)
        return (this.geq(r, 0, SVM.EPSILON) ||
                this.geq(v.alpha, svm.c, SVM.EPSILON)) &&
                (this.leq(r, 0, SVM.EPSILON) ||
                        this.leq(v.alpha, 0, SVM.EPSILON));
    }

    /**
     * The second choice heuristic. Chooses a vector that is likely to
     * maximise the size of the step taken during optimisation. This is
     * approximated by attempting to choose a vector such that the
     * absolute difference in error values between the two vectors is
     * maximised.
     *
     * @param error  the error value of the first vector
     * @return       the second vector
     */
    private SupportVector secondChoice(double error) {
        Entry<SupportVector,Double> best = null;
        if(error > 0) { // return vector with minimum error
            for(Entry<SupportVector,Double> entry : errorCache.entrySet())
                if(best == null || entry.getValue() < best.getValue())
                    best = entry;
        } else { // return vector with maximum error
            for(Entry<SupportVector,Double> entry : errorCache.entrySet())
                if(best == null || entry.getValue() > best.getValue())
                    best = entry;
        }
        return best.getKey();
    }

    /**
     * Optimise the given examples.
     *
     * @param v1  an example to optimise
     * @param v2  the other example to optimise
     * @return    true iff positive progress (a non-zero step size) was made
     */
    private boolean takeStep(SupportVector v1, SupportVector v2) {
        if(v1.x == v2.x)
            // identical inputs cause objective function to become
            // semi-definite, so positive progress cannot be made
            return false;
        final double alpha1 = v1.alpha, alpha2 = v2.alpha;
        final double y1 = v1.y, y2 = v2.y;

        // endpoints (in terms of values of alpha2) of the diagonal line
        // segment representing the constraint between the two alpha values
        double l, h;
        if(y1 != y2) {
            // equation (12.3)
            l = Math.max(0, alpha2 - alpha1);
            h = Math.min(svm.c, svm.c + alpha2 - alpha1);
        } else /* v1.y == v2.y */ {
            // equation (12.4)
            l = Math.max(0, alpha2 + alpha1 - svm.c);
            h = Math.min(svm.c, alpha2 + alpha1);
        }
        if(l == h) // the alpha values are constrained to a single point
            return false;

        final double k11 = svm.kernel.getValue(v1.x, v1.x),
                k12 = svm.kernel.getValue(v1.x, v2.x),
                k22 = svm.kernel.getValue(v2.x, v2.x);
        final double s = y1 * y2;
        final double e1 = error(v1), e2 = error(v2);

        // second derivative of the objective function along the diagonal line
        final double eta = k11 + k22 - 2*k12; // equation (12.5)
        // unusual circumstances
        if(eta < 0)
            throw new RuntimeException(
                    "The kernel function does not obey Mercer's condition");
        if(eta == 0) // two training examples have the same input vector
            return false;
        // normal circumstances - the objective function is positive
        // definite and there is a minimum along the diagonal line
        v2.alpha = alpha2 + y2 * (e1-e2) / eta; // equation (12.6)
        v2.alpha = this.clamp(v2.alpha, l, h); // equation (12.7)

        if(this.equals(v2.alpha, alpha2,
                SVM.EPSILON*(v2.alpha+alpha2+SVM.EPSILON))) {
            // change in alpha2 was too small
            v2.alpha = alpha2;
            return false;
        }

        v1.alpha = alpha1 + s*(alpha2-v2.alpha); // equation (12.8)
        v1.bound = this.leq(v1.alpha, 0, SVM.EPSILON) ||
                this.geq(v1.alpha, svm.c, SVM.EPSILON);
        v2.bound = this.leq(v2.alpha, 0, SVM.EPSILON) ||
                this.geq(v2.alpha, svm.c, SVM.EPSILON);

        // update threshold
        final double b = svm.b;
        final double delta1 = y1 * (v1.alpha - alpha1),
                delta2 = y2 * (v2.alpha - alpha2);
        final double b1 = e1 + delta1*k11 + delta2*k12, // equation (12.9)
                b2 = e2 + delta1*k12 + delta2*k22; // equation (12.10)
        svm.b += !v1.bound ? b1 : !v2.bound ? b2 : (b1+b2)/2;

        // update error cache
        for(SupportVector v : svm.vectors) {
            if(v.bound) continue; // bound examples are not cached
            if(v == v1 || v == v2) continue;
            final double k1 = svm.kernel.getValue(v1.x, v.x),
                    k2 = svm.kernel.getValue(v2.x, v.x);
            double error = errorCache.get(v);
            error += delta1*k1 + delta2*k2 + b - svm.b; // equation (12.11)
            errorCache.put(v, error);
        }
        if(!v1.bound) errorCache.put(v1, 0.0);
        else errorCache.remove(v1); // v1 is now bound - don't cache error
        if(!v2.bound) errorCache.put(v2, 0.0);
        else errorCache.remove(v2);

        return true;
    }

    boolean equals(double x, double y, double epsilon) {
        return Math.abs(x - y) < epsilon;
    }

    boolean leq(double x, double y, double epsilon) {
        return x <= y + epsilon;
    }

    boolean geq(double x, double y, double epsilon) {
        return x >= y - epsilon;
    }

    double clamp(double x, double low, double high) {
        return Math.min(Math.max(x, low), high);
    }
}

class SVM {
    public static final double EPSILON = 1e-3;
    /**
     * The support vectors
     */
    List<SupportVector> vectors = new ArrayList<>();
    /**
     * The threshold
     */
    double b = 0;
    /**
     * The kernel function
     */
    Kernel kernel;
    /**
     * The soft-margin parameter
     */
    double c;

    public SVM() {
        this.kernel = new LinearKernel();
        this.c = 1.0;
    }

    public void add(double[] vector, int y) {
        vectors.add(new SupportVector(vector, y));
    }

    public double output(double[] x) {
        // $u = \sum_j \alpha_j y_j K(x_j, x) - b$
        double u = -b;
        for(SupportVector v : vectors) {
            if(v.alpha <= EPSILON)
                // ignore non-support vectors that have not yet been pruned
                // this is only necessary to improve performance during
                // training, and doesn't do anything once prune() has been
                // called
                continue;
            u += v.alpha * v.y * kernel.getValue(v.x, x);
        }
        return u;
    }
}

class SupportVector {
    /** The input vector */
    final double[] x;
    /** The target class: either +1 or -1 */
    final byte y;
    /** The Lagrange multiplier for this example */
    double alpha = 0;
    /** Is the Lagrange multiplier bound? (Only used by SMO) */
    transient boolean bound = true;

    public SupportVector(double[] vector, int y) {
        if(Math.abs(y) != 1)
            throw new IllegalArgumentException("y must be either +1 or -1");
        this.x = vector;
        this.y = (byte) y;
    }
}

interface Kernel {
    double getValue(double[] x1, double[] x2);
}

class LinearKernel implements Kernel {
    public double getValue(double[] x1, double[] x2) {
        double prod = 0;
        for(int i = 0; i < x2.length; i++)
            prod += x1[i] * x2[i];
        return prod;
    }
}
