/****************************************************************************
Copyright (c) 2006, Colorado School of Mines and others. All rights reserved.
This program and accompanying materials are made available under the terms of
the Common Public License - v1.0, which accompanies this distribution, and is 
available at http://www.eclipse.org/legal/cpl-v10.html
****************************************************************************/
package edu.mines.jtk.bench;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static java.lang.Math.*;

import edu.mines.jtk.util.Array;
import edu.mines.jtk.util.Check;
import edu.mines.jtk.util.Stopwatch;

/**
 * Benchmark single- and multi-threaded matrix multiplication.
 * @author Dave Hale, Colorado School of Mines
 * @version 2006.07.02
 */
public class MtMatMulBench {

  public static void main(String[] args) {
    int m = 400;
    int n = 600;
    float[][] a = Array.randfloat(n,m);
    float[][] b = Array.randfloat(m,n);
    float[][] c1 = Array.zerofloat(m,m);
    float[][] c2 = Array.zerofloat(m,m);
    float[][] c3 = Array.zerofloat(m,m);
    Stopwatch s = new Stopwatch();
    double mflops = 2.0e-6*m*m*n;
    double nmul,maxtime=1.0;

    System.out.println("mul1 = single-threaded method");
    System.out.println("mul2 = atomic-integer method");
    System.out.println("mul3 = thread-pool method");

    for (int ntrial=0; ntrial<5; ++ntrial) {
      System.out.println();

      s.restart();
      for (nmul=0; s.time()<maxtime; ++nmul)
        mul1(a,b,c1);
      s.stop();
      System.out.println("mul1: rate="+(int)(nmul*mflops/s.time())+" mflops");

      s.restart();
      for (nmul=0; s.time()<maxtime; ++nmul)
        mul2(a,b,c2);
      s.stop();
      System.out.println("mul2: rate="+(int)(nmul*mflops/s.time())+" mflops");

      s.restart();
      for (nmul=0; s.time()<maxtime; ++nmul)
        mul3(a,b,c3);
      s.stop();
      System.out.println("mul3: rate="+(int)(nmul*mflops/s.time())+" mflops");
    }

    assertEquals(c1,c2);
    assertEquals(c1,c3);
  }

  /**
   * Computes j'th column in methods for matrix-matrix multiply below.
   * The work array bj is used to cache the j'th column of the matrix b.
   * Loop unrolling improves performance.
   */
  private static void computeColumn(
    int ni, int nk, int j, float[] bj, float[][] a, float[][] b, float[][] c) 
  {
    for (int k=0; k<nk; ++k)
      bj[k] = b[k][j];
    for (int i=0; i<ni; ++i) {
      float[] ai = a[i];
      float cij = 0.0f;
      int mk = nk%4;
      for (int k=0; k<mk; ++k)
        cij += ai[k]*bj[k];
      for (int k=mk; k<nk; k+=4) {
        cij += ai[k  ]*bj[k  ];
        cij += ai[k+1]*bj[k+1];
        cij += ai[k+2]*bj[k+2];
        cij += ai[k+3]*bj[k+3];
      }
      c[i][j] = cij;
    }
  }

  /**
   * Single-threaded straightforward method.
   */
  private static void mul1(float[][] a, float[][] b, float[][] c) {
    checkDimensions(a,b,c);
    int ni = c.length;
    int nj = c[0].length;
    int nk = b.length;
    float[] bj = new float[nk];
    for (int j=0; j<nj; ++j) {
      computeColumn(ni,nk,j,bj,a,b,c);
    }
  }

  /**
   * Multi-threaded lock-free version.
   */
  private static void mul2(
    final float[][] a, final float[][] b, final float[][] c) 
  {
    checkDimensions(a,b,c);
    final int ni = c.length;
    final int nj = c[0].length;
    final int nk = b.length;
    final AtomicInteger aj = new AtomicInteger();
    int nthread = 4;
    Thread[] threads = new Thread[nthread];
    for (int ithread=0; ithread<nthread; ++ithread) {
      threads[ithread] = new Thread(new Runnable() {
      final float[] bj = new float[nk];
        public void run() {
          for (int j=aj.getAndIncrement(); j<nj; j=aj.getAndIncrement())
            computeColumn(ni,nk,j,bj,a,b,c);
        }
      });
      threads[ithread].start();
    }
    try {
      for (int ithread=0; ithread<nthread; ++ithread)
        threads[ithread].join();
    } catch (InterruptedException ie) {
      throw new RuntimeException(ie);
    }
  }

  /**
   * Multi-threaded thread-pool version.
   */
  private static void mul3(
    final float[][] a, final float[][] b, final float[][] c) 
  {
    checkDimensions(a,b,c);
    final int ni = c.length;
    final int nj = c[0].length;
    final int nk = b.length;
    int nthread = 4;
    ExecutorService es = Executors.newFixedThreadPool(nthread);
    CompletionService<Void> cs = new ExecutorCompletionService<Void>(es);
    for (int j=0; j<nj; ++j) {
      final int jj = j;
      cs.submit(new Runnable() {
        public void run() {
          computeColumn(ni,nk,jj,bj,a,b,c);
        }
        private float[] bj = new float[nk];
      },null);
    }
    try {
      for (int j=0; j<nj; ++j)
        cs.take();
    } catch (InterruptedException ie) {
      throw new RuntimeException(ie);
    }
    es.shutdown();
  }

  /**
   * Single-threaded blocked version. Currently unused.
   */
  private static void mulb(float[][] a, float[][] b, float[][] c) {
    checkDimensions(a,b,c);
    int ni = c.length;
    int nj = c[0].length;
    int nk = b.length;
    int mi = 16;
    int mj = 16;
    int mk = 16;
    float[] bjj = new float[nk];
    for (int i=0; i<ni; i+=mi) {
      int nii = min(i+mi,ni);
      for (int j=0; j<nj; j+=mj) {
        int njj = min(j+mj,nj);
        for (int ii=i; ii<nii; ++ii)
          for (int jj=j; jj<njj; ++jj)
            c[ii][jj] = 0.0f;
        for (int k=0; k<nk; k+=mk) {
          int nkk = min(k+mk,nk);
          for (int jj=j; jj<njj; ++jj) {
            for (int kk=k; kk<nkk; ++kk)
              bjj[kk] = b[kk][jj];
            for (int ii=i; ii<nii; ++ii) {
              float[] aii = a[ii];
              float cij = c[ii][jj];
              for (int kk=k; kk<nkk; ++kk)
                cij += aii[kk]*bjj[kk];
              c[ii][jj] = cij;
            }
          }
        }
      }
    }
  }

  private static void assertEquals(float[][] a, float[][] b) {
    Check.state(a.length==b.length,"same dimensions");
    Check.state(a[0].length==b[0].length,"same dimensions");
    int m = a[0].length;
    int n = a.length;
    for (int i=0; i<m; ++i) {
      for (int j=0; j<n; ++j) {
        Check.state(a[i][j]==b[i][j],"same elements");
      }
    }
  }

  private static void checkDimensions(float[][] a, float[][] b, float[][] c) {
    int ma = a.length;
    int na = a[0].length;
    int mb = b.length;
    int nb = b[0].length;
    int mc = c.length;
    int nc = c[0].length;
    Check.argument(na==mb,"number of columns in A = number of rows in B");
    Check.argument(ma==mc,"number of rows in A = number of rows in C");
    Check.argument(nb==nc,"number of columns in B = number of columns in C");
  }
}