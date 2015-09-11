package searching;


import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.math3.special.Gamma;
import static org.apache.commons.math3.special.Gamma.digamma;
import static org.apache.commons.math3.special.Gamma.logGamma;


/**
 * 
 * 
 * Estimating a DCM (Dirichlet Compound Multinomial) using fixed-point method in 
 * Estimating a Dirichlet distribution by Thomas P. Minka
 * 
 * In memory solution using a set of input count-vectors (as Maps). 
 * 
 * 
 * 
 * @author Ronan Cummins
 * 
 */
public class DCMModel {

    
    //a pointer to the data which is an 
    //array of histograms (or count vectors)
    private final Map<String, Double> [] data;
    private final Double[] vector_mass;
    private int sum_unique_terms;
    public static int iterations = 20;
    
    
    //parameter estimates
    private final Map<String, Double> alpha;
    
    
    //mass of parameters
    private double prev_A = Double.POSITIVE_INFINITY;
    private double cur_A;

    //loglikelihood values
    private double prev_loglikeli = Double.POSITIVE_INFINITY;
    private double cur_loglikeli;
    
    
    public static double epsilon;
    
    public DCMModel(Map<String, Double>[] _data){
        data = _data;
        

        sum_unique_terms=0;
        alpha = new TreeMap<>();
        
        //store mass of each count vector and set initial estimates to num of samples 
        //a type appears in (i.e. an estimate proportional to the EDCM)
        vector_mass = new Double[data.length];
        Double f;
        Double c;
        for (int i=0;i<data.length;i++){
            Double m=0.0;
            for (String key:data[i].keySet()){
                c = data[i].get(key);
                m += c;
               
                f = alpha.get(key);
                if (f==null) f=0.0;
                alpha.put(key, f + 1);
                cur_A+=1.0;
                sum_unique_terms++;
               
            }
            //System.out.println(data[i].size());
            vector_mass[i] = m;
        }
        
        
        
        
        
    }
    
    
    
    public Map<String, Double> getParameters(){
        return alpha;
    }
    
    public double getMass(){
        return cur_A;
    }
    
    /**
     * 
     * 
     * call the estimateDCM method to estimate the parameters
     * which are then returned in a Map<String, Double> 
     * 
     * @return 
     */
    
    public Map<String, Double> estimateDCM(){
        
        double den,num;
        double cur_p;
        double diff;
        double loglikeli;
        
        for(int i=0;i<DCMModel.iterations;i++){
            
            cur_loglikeli = 0.0;
            
            den = denom(alpha);
            
            
            
            for(String dim:alpha.keySet()){
                cur_p   = alpha.get(dim);
                num = numerator(dim, cur_p);
                //System.out.println("multiplier " + (num/den));
                alpha.put(dim, cur_p*num/den );
                //estimates.put(dim, 1.0 );
                
                
                
            }
            
            diff = Math.abs(cur_loglikeli-prev_loglikeli);
            if (diff < DCMModel.epsilon){
                break;
            }
            
            
            this.prev_loglikeli = this.cur_loglikeli;
            
            //System.out.println("loglikelihood: " + this.cur_loglikeli + "\t" + this.alpha);
        }
        
        //System.out.println(alpha);
        
        
        return alpha;
    }
    
    
    /**
     * 
     * 
     * call the estimateEDCM method to return the EDCM estimates
     * Quicker than calling estimateDCM since one only needs to 
     * estimate the background mass 
     * estimates are returned in a Map<String, Double> 
     * see Clustering Documents with an Exponential-Family Approximation of the
     * Dirichlet Compound Multinomial Distribution by Charles Elkan
     * 
     * @return 
     */    
    
    public Map<String, Double> estimateEDCM(){
        
        alpha.clear();
        
        //initialise the EDCM probabilities
        Double f;
        Double c;
        sum_unique_terms=0;
        for (int i=0;i<data.length;i++){
            Double m=0.0;
            for (String key:data[i].keySet()){
                c = data[i].get(key);
                m += c;
               
                f = alpha.get(key);
                if (f==null) f=0.0;
                alpha.put(key, f + 1);
                cur_A+=1.0;
                sum_unique_terms++;
            }
            vector_mass[i] = m;
        }        
        
        
        
        
        //calculate the mass a0
        double a0 = 200, denom;
        double dl;
        for (int i = 0; i < iterations; i++) {
            //System.out.println("iteration " + i + " estimated mu value is " + a0);
            denom = 0;

            for (int j = 0; j < data.length; j++) {
                dl = this.vector_mass[j];
                denom += Gamma.digamma(a0 + (double)dl);
            }

            denom = (denom - (data.length * Gamma.digamma(a0)));

            a0 = this.sum_unique_terms / denom;

        }    
        
        

        //update the parameters with mass a0
        double est;
        for (String dim: alpha.keySet()){
         
            f = alpha.get(dim);
            est = f*a0/this.sum_unique_terms;
            
            alpha.put(dim, est);
        }
        
        cur_A = a0;
        
        //System.out.println(alpha);
        return alpha;
        
    }
    
    
    
    /**
     * 
     * call the denom once for each iteration as it only depends on the 
     * previous iteration estimates
     * @param cur_est
     * @return 
     */
    public double denom(Map<String, Double> cur_est){
        
        double A = 0.0;

        for(String key:cur_est.keySet()){
            A += cur_est.get(key);
        }
        
        //System.out.println("Current Mass of Estimates: " + mass);
        cur_A = A;
        
        double denom=0.0;
        for (int i=0;i<data.length;i++){
            denom += digamma(vector_mass[i] + A) - digamma(A);
            cur_loglikeli += logGamma(A) - logGamma(vector_mass[i] + A);
            //System.out.println((vector_mass[i] + A) + "\t" + logGamma(vector_mass[i] + A) + "\t" +  logGamma(A));
        }
        
        
        
        
        return denom;
        
    }
    
    
    
    
    /**
     * 
     * get the dimension numerator update
     * 
     * @param dim
     * @param param
     * @return 
     */
    public double numerator(String dim, double ak){
        
        Double f;
        double numer =0.0;
        
        for (int i=0;i<data.length;i++){
            
            f = data[i].get(dim);
            if (f!=null){
                numer += digamma(f+ak) - digamma(ak);
                cur_loglikeli += logGamma(f+ak) - logGamma(ak);
            }
        }
        
        
        //System.out.println(dim + " Numer \t" + numer);
        
        return numer;
    }
    
    
    
    

    

    
    
    /**
     * 
     * main 
     * create some samples
     * Tested with respect values returned form dirmult package in R
     * 
     * 
     * This main is simply for testing
     * 
     * @param args 
     */
    public static void main(String[] args){
       
        TreeMap d1 = new TreeMap();
        TreeMap d2 = new TreeMap();
        TreeMap d3 = new TreeMap();
        
        d1.put("a", 100);
        d1.put("b", 100);
        d1.put("c", 175);
        d1.put("d", 250);
        d1.put("e", 175);
        d1.put("f", 100);
        d1.put("g", 100);

        
        d2.put("a", 2);
        d2.put("b", 0);
        d2.put("c", 0);
        d2.put("d", 0);
        d2.put("e", 0);
        d2.put("f", 0);
        d2.put("g", 0);

        

        d3.put("a", 10);
        
        Map[] data = {d1,d2,d3};

        //
        // now we have some dummy data
	//
        
        
        //set max number of iterations and the stopping epsilon
        DCMModel.iterations = 100;
        DCMModel.epsilon = 0.01;
        
        //create a DCM model with data and call estimate DCM
        DCMModel dcm = new DCMModel(data);
        Map<String, Double> params = dcm.estimateDCM();
        
        
        //examine the results
        
        double m=0.0;
        for(String dim:params.keySet()){
            m += params.get(dim);
        }
        
        System.out.println("Mass: " + m);
        System.out.println(params);
        
    }
    
    
}

