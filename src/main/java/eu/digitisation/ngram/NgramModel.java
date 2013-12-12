/*
 * Copyright (C) 2013 Universidad de Alicante
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package eu.digitisation.ngram;

import eu.digitisation.io.WordScanner;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A n-gram model for strings. N is the maximal order of the model (context
 * length plus one).
 */
class NgramModel implements Serializable {

    static final long serialVersionUID = 1L;
    static final String BOT = "\u0002";      // Begin of text marker.
    static final String EOT = "\u0003";      // End of text marker.

    int order;                   // The size of the context plus one (n-gram).
    HashMap<String, Int> occur;  // Number of occurrences.
    double[] lambda;             // Backoff parameters

    /**
     * Set maximal order of model.
     *
     * @param n the size of the context plus one (the n in n-gram).
     */
    public final void setOrder(int order) {
        if (occur == null || occur.isEmpty()) {
            if (order > 0) {
                this.order = order;
            } else {
                System.err.println("Order must be grater than 0");
            }
        } else {
            System.err.println("Cannot change order of model with previous content");
        }
    }

    /**
     * Class constructor (default order is 2).
     */
    public NgramModel() {
        setOrder(2);
        occur = new HashMap<String, Int>();
        lambda = null;

    }

    /**
     * Class constructor.
     *
     * @param order the size of the context plus one.
     */
    public NgramModel(int order) {
        setOrder(order);
        occur = new HashMap<String, Int>();
        lambda = null;
    }

    /**
     * @return number of different n-grams stored
     */
    public int size() {
        return occur.keySet().size();
    }

    /**
     * Save n-gram model to file
     *
     * @param file the output file
     */
    public void save(File file) {
        try {
            FileOutputStream fos = new FileOutputStream(file);
            GZIPOutputStream gos = new GZIPOutputStream(fos);
            ObjectOutputStream out = new ObjectOutputStream(gos);
            out.writeObject(this);
            out.close();
        } catch (IOException ex) {
            Logger.getLogger(NgramModel.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Build n-gram model from file
     *
     * @param file the input file
     */
    public NgramModel(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            GZIPInputStream gis = new GZIPInputStream(fis);
            ObjectInputStream in = new ObjectInputStream(gis);
            NgramModel ngram = (NgramModel) in.readObject();
            in.close();
            this.order = ngram.order;
            this.occur = ngram.occur;
            this.lambda = ngram.lambda;
        } catch (IOException ex) {
            Logger.getLogger(NgramModel.class.getName()).log(Level.SEVERE, null, ex);
        } catch (ClassNotFoundException ex) {
            Logger.getLogger(NgramModel.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * @param n n-gram order.
     * @return Good-Turing back-off parameter.
     */
    private double lambda(int n) {
        if (lambda == null) {
            lambda = new double[order];
            int[] total = new int[order];
            int[] singles = new int[order];
            for (String word : occur.keySet()) {
                if (word.length() > 0) {
                    int k = word.length() - 1;
                    int times = occur.get(word).getValue();
                    total[k] += times;
                    if (times == 1) {
                        ++singles[k];
                    }
                }
            }
            for (int k = 0; k < order; ++k) {
                lambda[k] = singles[k] / (double) total[k];
            }
        }
        return lambda[n];
    }

    /**
     * @param s a k-gram
     * @return the (k-1)-gram obtained by removing its first character.
     */
    private String tail(String s) {
        return s.substring(1);
    }

    /**
     * @param s a k-gram
     * @return the (k-1)-gram obtained by removing its last character.
     */
    private String head(String s) {
        return s.substring(0, s.length() - 1);
    }

    /**
     * @return the number of text entries (usually words) building the model.
     */
    private int numWords() {
        return occur.get(EOT).getValue(); // end-of-word.
    }

    /**
     * @param s a k-gram (k > 0)
     * @return the conditional probability of the k-gram, normalized to the
     * number of heads.
     */
    private double prob(String s) {
        if (occur.containsKey(s)) {
            String h = head(s);
            if (h.endsWith(BOT)) {  // if head is not stored
                return occur.get(s).getValue() / (double) numWords();
            } else {
                return occur.get(s).getValue()
                        / (double) occur.get(h).getValue();
            }
        } else {
            return 0;
        }
    }

    /**
     * @param s a k-gram
     * @return The conditional probability of the k-gram, normalized to the
     * frequency of its heads and interpolated with lower order models.
     */
    private double smoothProb(String s) {
        double result;
        if (s.length() > 1) {
            double lam = lambda(s.length() - 1);
            result = (1 - lam) * prob(s) + lam * smoothProb(tail(s));
        } else {
            result = prob(s);
        }
        return result;
    }

    /**
     * @param s a k-gram
     * @return The expected number of occurrences (per word) of s.
     */
    private double expectedNumberOf(String s) {
        if (s.endsWith(BOT)) {
            return 1;
        } else {
            return occur.get(s).getValue() / (double) numWords();
        }
    }

    /**
     * Increments number of occurrences of s.
     *
     * @param s a k-gram.
     */
    private void addEntry(String s) {
        if (occur.containsKey(s)) {
            occur.get(s).increment();
        } else {
            occur.put(s, new Int(1));
        }
    }

    /**
     * Increments number of occurrences of s.
     *
     * @param s a k-gram.
     * @param n number of occurrences
     */
    private void addEntries(String s, int n) {
        if (occur.containsKey(s)) {
            occur.get(s).add(n);
        } else {
            occur.put(s, new Int(n));
        }
    }

    /**
     * Compute n-gram model log entropy per word (in bits).
     *
     * @return log entropy per word (in bits).
     */
    public double entropy() {
        double p, sum = 0;
        for (String s : occur.keySet()) {
            if (s.length() == order) {
                p = prob(s);
                sum -= expectedNumberOf(head(s)) * p * Math.log(p);
            }
        }
        return sum / Math.log(2);
    }

    /**
     * Several types of distances will be implemented here
     * @param other
     * @return 
     */
    public int distance(NgramModel other) {
        int d = 0;
        for (String s : occur.keySet()) {
            int delta = this.occur.get(s).getValue()
                    - other.occur.get(s).getValue();
            d += Math.abs(delta);
        }
        return d;
    }

    /**
     * Extracts all k-grams in a word or text upto the maximal order. For
     * instance, if word = "ma" and order = 3, then 0-grams are: "" (three empty
     * strings, to normalize 1-grams). 1-grams: "m, a, $" ($ represents
     * end-of-word). 2-grams: "#m, ma, a$" (# is used to differentiate #m from
     * 1-gram m). 3-grams: "##m, #ma, ma$"
     *
     * @remark do NOT add 1-gram "#" or 1-gram normalization will be wrong.
     * @param word the word to be added.
     */
    public void addWord(String word) {
        if (word.length() < 1) {
            System.err.println("Cannot extract n-grams from " + word);
            System.exit(1);
        } else {
            word = word + EOT;
        }
        String s = new String();
        while (s.length() < order) {
            s += BOT;
        }
        for (int last = 0; last < word.length(); ++last) {
            s = tail(s) + word.charAt(last);
            for (int first = 0; first <= s.length(); ++first) {
                addEntry(s.substring(first));
            }
        }
    }

    /**
     * Add all k-grams in a word or text
     *
     * @param word the word or text to be processed
     * @param times the number of occurrences of the word or text
     */
    public void addWords(String word, int times) {
        if (word.length() < 1) {
            System.err.println("Cannot extract n-grams from " + word);
            System.exit(1);
        } else {
            word = word + EOT;
        }
        String s = new String();
        while (s.length() < order) {
            s += BOT;
        }
        for (int last = 0; last < word.length(); ++last) {
            s = tail(s) + word.charAt(last);
            for (int first = 0; first <= s.length(); ++first) {
                addEntries(s.substring(first), times);
            }
        }
    }

    /*
     * Reads text file and adds words to model.
     * @param fileName a text file
     * @param caseSensitive true if extracted n-grams are case sensitive
     */
    public void addTextFile(File file, String encoding, boolean caseSensitive) {
        try {
            WordScanner scanner = new WordScanner(file, encoding);
            String word;
            while ((word = scanner.nextWord()) != null) {
                if (caseSensitive) {
                    addWord(word);
                } else {
                    addWord(word.toLowerCase());
                }
            }
        } catch (IOException ex) {
            Logger.getLogger(NgramModel.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    /**
     * Compute probability of a word.
     *
     * @param a word.
     * @return the log-probability (base e) of the contained n-grams.
     */
    public double wordLogProb(String word) {
        double res = 0;
        if (word.length() < 1) {
            System.err.println("Cannot compute probability of " + word);
            System.exit(1);
        } else {
            word = word + EOT;
        }
        String s = new String();
        while (s.length() < order) {
            s += BOT;
        }
        for (int last = 0; last < word.length(); ++last) {
            s = tail(s) + word.charAt(last);

            double p = smoothProb(s);
            if (p == 0) {
                System.err.println(s + " has 0 probability");
                return Double.NEGATIVE_INFINITY;
            } else {
                res += Math.log(p);
            }
        }
        return res;
    }

    /*
     * Reads input text and computes cross entropy.
     * @return the log-likelihood of text.
     */
    public double logLikelihood(boolean caseSensitive) {
        try {
            String encoding = System.getProperty("file.encoding");
            WordScanner scanner
                    = new WordScanner(System.in, encoding);
            String word;
            double result = 0;
            int numWords = 0;
            while ((word = scanner.nextWord()) != null) {
                ++numWords;
                if (caseSensitive) {
                    result -= wordLogProb(word);
                } else {
                    result -= wordLogProb(word.toLowerCase());
                }
            }

            return result / numWords / Math.log(2);
        } catch (IOException ex) {
            Logger.getLogger(NgramModel.class.getName()).log(Level.SEVERE, null, ex);
        }
        return Double.POSITIVE_INFINITY;
    }

    /*
     * Main function.
     */
    public static void main(String[] args) {
        NgramModel ngram = new NgramModel();
        String encoding = System.getProperty("file.encoding");
        File fout = null;

        if (args.length == 0) {
            System.err.println("Usage: Ngram [-n n] [-e encoding] [-o outfile]"
                    + " file1 file2 ....");
        } else {
            for (int k = 0; k < args.length; ++k) {
                String arg = args[k];

                if (arg.equals("-n")) {
                    ngram.setOrder(new Integer(args[++k]));
                } else if (arg.equals("-e")) {
                    encoding = args[++k];
                } else if (arg.equals("-o")) {
                    fout = new File(args[++k]);
                } else {
                    ngram.addTextFile(new File(arg), encoding, false);
                }
            }
            if (fout != null) {
                ngram.save(fout);
            } else {
                System.out.println(ngram.entropy());
                System.out.println(ngram.logLikelihood(false));
            }
        }
    }
}
