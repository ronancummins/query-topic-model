/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package searching;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.similarities.SPUDLMSimilarity;
import utils.Utils;

/**
 *
 * @author ronanc
 */
public class QueryExpansion {
    
    private final static Logger logger = Logger.getLogger(QueryExpansion.class.getName());
    
    public static boolean EXPAND = false;
    public static int method = 1;
    
    // all methods implemented
    public final static int RM3 = 1;
    public final static int SPUDQTM = 2;
    public final static int SPUDQTM2 = 9;
    public final static int DIRQTM = 3;
    public final static int PDCM = 4;
    public final static int SMM = 5;
    public final static int PRM1 = 6;
    public final static int PRM2 = 7;
    
    
    
    //# of pseudo relevant docs to consider
    public static int pseudo_rel_docs = 10;
    
    //# of expansion word types (dimsensions to add)
    public static int num_expansion_terms = 25;
    
    
    //distance parameter in PRM models
    public static double prm_sigma = 200.0;
    
    //lambda parameter in SMM (Default should be low)
    public static double ssm_lambda = 0.2;
    
    //Default parameter 0.8 works great
    public static double spud_omega = 0.8;
    
    //mass of expansion terms compared to original query
    public static double interpolation = 0.5;
    
    
    public TreeMap<String,Double>[] pdocs;
    public Integer[] pdoc_lengths;
    public Double[] pdoc_positional_lengths; // can be used if one needs PRM2
    public Double[] pdoc_scores;
    
    //actual docs used
    private int actual_pdocs;
    
    
    
    public QueryExpansion(){
        pdocs = new TreeMap[pseudo_rel_docs];
        pdoc_lengths = new Integer[pseudo_rel_docs];
        pdoc_positional_lengths = new Double[pseudo_rel_docs];
        pdoc_scores = new Double[pseudo_rel_docs];
        
        actual_pdocs = 0;
        
    }
    
    /**
     * 
     * store frequencies of top docs in maps
     * 
     * @param text
     * @param doc_score
     * @param analyzer
     * @param reader
     * @throws IOException 
     */    
    public void addExpansionDoc(String text, double doc_score, Analyzer analyzer, IndexReader reader) throws IOException{
        
    
        if (actual_pdocs < QueryExpansion.pseudo_rel_docs) {

            TreeMap<String, Double> map = new TreeMap();
            
            Integer length = 0;
            Double f;
            
            
            String term;
            TokenStream ts = analyzer.tokenStream("myfield", new StringReader(text));
            //OffsetAttribute offsetAtt = ts.addAttribute(OffsetAttribute.class);
            try {
                ts.reset(); // Resets this stream to the beginning. (Required)
                while (ts.incrementToken()) {
                    term = ts.getAttribute(CharTermAttribute.class).toString();
                    if (term.length() > 1) {
                        
                        
                        f = map.get(term);

                        if (f == null) {
                            map.put(term, 1.0);
                        } else {
                            map.put(term, f + 1.0);
                        }
                        length++;
                    }
                }
                ts.end();
            } finally {
                ts.close();
            }
            
            


            pdocs[actual_pdocs] = map;
            pdoc_lengths[actual_pdocs] = length;
            pdoc_scores[actual_pdocs] = doc_score;
            
            //logger.info(observed_bg_mass[iter] + "\t" + (1-observed_bg_prob));
            actual_pdocs++;
        }
    }
    
    /**
     * calculate positional relevance weights
     * 
     * @param query
     * @param text
     * @param doc_score
     * @param analyzer
     * @param reader
     * @throws IOException 
     */
    
    public void addPositionalExpansionDoc(CustomQuery query, String text, double doc_score, Analyzer analyzer, IndexReader reader) throws IOException{
        
        //System.out.println(query);
        //System.out.println(text);
        
        if (actual_pdocs < QueryExpansion.pseudo_rel_docs) {

            
            TreeMap<String, ArrayList<Long>> query_term_pos = new TreeMap();
            Integer length = 0;
            
            
            
            
            Long pos=1L;
            String term;
            TokenStream ts = analyzer.tokenStream("myfield", new StringReader(text));
            
            ArrayList<Long> qpos;
            //OffsetAttribute offsetAtt = ts.addAttribute(OffsetAttribute.class);
            try {
                ts.reset(); // Resets this stream to the beginning. (Required)
                while (ts.incrementToken()) {
                    term = ts.getAttribute(CharTermAttribute.class).toString();
                    if (term.length() > 1) {
                        
                        
                        //System.out.print(pos + ":" + term + " ");
                        if (query.contains(term)) {
                            qpos = query_term_pos.get(term);
                            if (qpos == null) {
                                qpos = new ArrayList<>();
                            }
                            qpos.add(pos);
                            query_term_pos.put(term, qpos);
                        }

                        length++;
                        pos++;
                    }
                }
                ts.end();
            } finally {
                ts.close();
            }
            
            //
            // All positions collected
            // now iterate over the document again to get weights
            //
            //System.out.println("Doc length" + text.length());
            //System.out.println("Positions... ");
            //System.out.println(query_term_pos.toString());
            //System.out.println("END...");
            TreeMap<String, Double> map = new TreeMap();
            Double f;
            pos=1L;
            double w, w_norm, prob , f0;
            Double pos_length = 0.0;
            Double sum_df = (double)reader.getSumDocFreq("text");
            double spud_pi = SPUDLMSimilarity.b0 * SPUDLMSimilarity.omega / (query_term_pos.size() * (1 - SPUDLMSimilarity.omega) + SPUDLMSimilarity.b0 * SPUDLMSimilarity.omega);
            Double df;
            double dist;
            
            ts = analyzer.tokenStream("myfield", new StringReader(text));
            try {
                ts.reset(); // Resets this stream to the beginning. (Required)
                while (ts.incrementToken()) {
                    term = ts.getAttribute(CharTermAttribute.class).toString();
                    if (term.length() > 1) {
                        
                        
                        prob=0.0;
                        //f is occurrence
                        w_norm = Math.sqrt(2*Math.PI*prm_sigma*prm_sigma);
                        for(String qt:query_term_pos.keySet()){
                            ArrayList<Long> pos_list = query_term_pos.get(qt);
                            w=1.0;
                            df = (double) reader.docFreq(new Term("text", qt));
                            for(Long p:pos_list){
                                dist = ((pos-p)*(pos-p))/(2*prm_sigma*prm_sigma);
                                f0 = Math.exp(-dist);

                                //if (QueryExpansion.method == QueryExpansion.PRM2QTM){
                                    //w += (((double) ((1 - spud_pi) * f0) / (((1 - spud_pi) * f0 ) + spud_pi * (df / sum_df))));
                                //    w += f0;
                                //}else{
                                    w += f0;
                                //}
                                
                            }
                            //System.out.println("weight " + w );
                            prob += Math.log(w/w_norm);
                        }
                        
                        //System.out.print(pos + "\t" + term + "\t" +  Math.exp(prob) + "\n");
                        
                        /** sum of the probabilities over the positional terms in the documents*/
                        f = map.get(term);
                        if (f == null){
                            map.put(term, Math.exp(prob));
                        }else{
                            map.put(term, f + Math.exp(prob));
                        }
                        pos_length += Math.exp(prob);
                        pos++;
                    }
                }
                ts.end();
            } finally {
                ts.close();
            }
            
            double sum = 0.0;
            for(String word : map.keySet()){
                //logger.info(word + "\t" + map.get(word)/pos_length);
                sum += map.get(word)/pos_length;
            }
            //logger.info("sum is " + sum);
            
            

            pdocs[actual_pdocs] = map;
            pdoc_lengths[actual_pdocs] = length;
            pdoc_positional_lengths[actual_pdocs] = pos_length;
            pdoc_scores[actual_pdocs] = doc_score;
            
            
            actual_pdocs++;
        }
    }    
    
    
    
    /**
     * 
     * @return 
     */
    public Map<String,Double> expansionTerms(IndexReader reader,CustomQuery query, int query_length) throws IOException{
        
        Map<String, Double> expansion_terms = new TreeMap();
        Map<String, Double> map;
        Double f;
        Double e, prob;
        Double df;
        Double sum_df = (double)reader.getSumDocFreq("text");
        Double cf;
        Double sum_cf = (double)reader.getSumTotalTermFreq("text");
        
        Double score_norm=0.0;
        if (QueryExpansion.method == QueryExpansion.PDCM){
            
            //logger.info(actual_pdocs + " docs" + this.pdocs.length);
            expansion_terms = this.DCM().estimateDCM();
            

        }else if (QueryExpansion.method == QueryExpansion.SMM){
            
            //get SMM estimates
             
            expansion_terms = this.SMM(reader,20);
            
            
            
        }else{

            for (int i = 0; i < pseudo_rel_docs; i++) {

                map = this.pdocs[i];
                if (map != null) {
                    
                    double spud_pi = SPUDLMSimilarity.b0 * QueryExpansion.spud_omega / 
                            (map.size() * (1 - QueryExpansion.spud_omega) + SPUDLMSimilarity.b0 * QueryExpansion.spud_omega);
                    double dir_pi = SPUDLMSimilarity.dir_mu / (this.pdoc_lengths[i] + SPUDLMSimilarity.dir_mu);
                    
                    for (String term : map.keySet()) {

                        double tf = (double)map.get(term);
                        
                        if (!term.contains(":")) {
                            df = (double) reader.docFreq(new Term("text", term));
                            cf = (double) reader.totalTermFreq(new Term("text", term));
                            //logger.info(new Term(term) + "\t" + df + "\t" + sum_df);
                            //RM3
                            if (QueryExpansion.method == QueryExpansion.RM3) {
                                //RM3 with u=0
                                e = ((double) tf / this.pdoc_lengths[i]) * Math.exp(this.pdoc_scores[i]);
                                
                                //e = ((1-spud_pi)*((double) tf / this.pdoc_lengths[i]) +  spud_pi*(df / sum_df)) * Math.exp(this.pdoc_scores[i]);
                                
                            } else if (QueryExpansion.method == QueryExpansion.DIRQTM) {
                                //Dir Topic Model
                                e = (((double) ((1 - dir_pi) * tf / this.pdoc_lengths[i]) / (((1 - dir_pi) * tf / this.pdoc_lengths[i]) + dir_pi * (cf / sum_cf)))) * Math.exp(this.pdoc_scores[i]);
                            } else if ((QueryExpansion.method == QueryExpansion.SPUDQTM)||(QueryExpansion.method == QueryExpansion.SPUDQTM2)) {
                                //SPUD Topic Model
                                prob = (((double) ((1 - spud_pi) * tf / this.pdoc_lengths[i]) / (((1 - spud_pi) * tf / this.pdoc_lengths[i]) + spud_pi * (df / sum_df))));
                                e =  prob * Math.exp(this.pdoc_scores[i]);
                            } else if (QueryExpansion.method == QueryExpansion.PRM1) {
                                //Positional Relevance Model 1
                                e = ((double) tf / this.pdoc_lengths[i]) * Math.exp(this.pdoc_scores[i]);
                            }else if (QueryExpansion.method == QueryExpansion.PRM2) {
                                //Positional Relevance Model 2
                                e = ((double) tf / this.pdoc_positional_lengths[i]) * Math.exp(this.pdoc_scores[i]);
                            }else {
                                //default RM3
                                e = ((double) tf / this.pdoc_lengths[i]) * Math.exp(this.pdoc_scores[i]);
                            }

                            f = expansion_terms.get(term);
                            if (f == null) {
                                expansion_terms.put(term, e);
                            } else {
                                expansion_terms.put(term, e + f);
                            }
                        }

                    }
                    
                    score_norm += Math.exp(this.pdoc_scores[i]);
                    //logger.info(i + "\t" + Math.exp(this.pdoc_scores[i]));

                }

            }

        }
        
       
        
        Double norm =0.0, topic_prob;
        Double topical_mass = 0.0;
        int t=0;
        //sort
        ArrayList list = sortValue(expansion_terms);
        
        //create query-topic_model for QTM probability
        TreeMap<String, Double> query_topic_model = new TreeMap();
        for (int i=0;(i<num_expansion_terms)&&(i<list.size());i++){
           
           Double tsv =  (double)((Map.Entry)list.get(i)).getValue();
           String term = ((Map.Entry)list.get(i)).getKey().toString();
           topic_prob = tsv/score_norm;
           topical_mass+= topic_prob;
           
           norm += tsv;
           t++;
           
           query_topic_model.put(term, topic_prob);
           //System.out.println(term + "\t" + topic_prob + "\t" +  (double)((Map.Entry)list.get(i)).getValue());
        }
        
        /*
        if (QueryExpansion.method == QueryExpansion.SPUDQTM2){
            Double gen = this.QueryModelLikelihood(reader, query, query_topic_model);
            logger.info("Topic score " + gen + "\t" + query.mass());
            QueryExpansion.interpolation =  gen;
        }
        */
        
        
        //now just grab the selected terms and normalised to sum to 1.0
        TreeMap<String, Double> selected_terms = new TreeMap();
        double sum = 0;
        for (int i=0;(i<t)&&(i<list.size());i++){
            
            f =  (double)((Map.Entry)list.get(i)).getValue();
            ((Map.Entry)list.get(i)).setValue(f/norm);
            selected_terms.put(((Map.Entry)list.get(i)).getKey().toString(), f/norm);
            sum += f/norm;
        }        
        
        return selected_terms;
    }
    
    
    /**
     * smooth the expansion distribution with the original query
     * @param query
     * @param e_terms 
     */
    public void expandCustomQuery(IndexReader reader, CustomQuery query, Map<String,Double> e_terms) throws IOException{
        
        double expansion_mass;
        
        expansion_mass = query.mass() * QueryExpansion.interpolation/(1.0-QueryExpansion.interpolation);
        
        
        Double f;
        for (String term: e_terms.keySet()){
            
            //add the expansion term to the query object
            query.add(term, (expansion_mass * e_terms.get(term)));
        }
        
        //logger.info(query.examine());
        
    }
    
    
    
    /**
     * return list of sorted terms
     * @param t
     * @return 
     */
    
    public static ArrayList sortValue(Map<String, Double> t) {

        //Transfer as List and sort it
        ArrayList<Map.Entry<String, Double>> l = new ArrayList(t.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<String, Double>>() {

            public int compare(Map.Entry<String, Double> o1, Map.Entry<String, Double> o2) {
                return o2.getValue().compareTo(o1.getValue());
            }
        });

        return l;
    } 
    
    
    
    /**
     * 
     * estimate the DCM parameters using pdocs arrays
     * 
     * @return 
     */
    private DCMModel DCM(){
        
        DCMModel.iterations = 15;
        DCMModel.epsilon = 0.01;
        
        
        TreeMap<String,Double>[] temp = new TreeMap[this.actual_pdocs];
        for (int i=0;i<this.actual_pdocs;i++){
            temp[i] = pdocs[i];
        }
        DCMModel pseudo_rel_model = new DCMModel(temp);
        
        return pseudo_rel_model;
        
    }
    
    
    

    
    /**
     * 
     * estimate the SMM using Expectation Maximization for Multinomial
     * 
     * @return 
     */
    private Map<String,Double> SMM(IndexReader reader, int iterations) throws IOException{
        
        double avg_dl=0.0;
        double mass = 0.0;
        for (int i=0;i<this.actual_pdocs;i++){
            
            mass += (double)pdoc_lengths[i];
        }
        

        //double lambda = 0.0 ;
        //get initial estimate counts
        
        Map<String, Double> counts = new TreeMap();
        Double f,est;
        for (int i=0;i<this.actual_pdocs;i++){
            
            if (pdocs[i] != null) {
                for (String term : this.pdocs[i].keySet()) {
                    f = this.pdocs[i].get(term);

                    est = counts.get(term);

                    if (est == null) {
                        counts.put(term, f);
                    } else {
                        counts.put(term, f + est);
                    }
                }
            }
        }        
        
        
        
        
        //now we have initial estimates of the maximum likelhood multinomial
        //use EM to find likelihood given the background model and fixed mixture parameter
        
        TreeMap<String, Double> rel_likelihoods = new TreeMap();
        Double cf, ptF,ptC, rl,co;
        Double sum_cf = (double)reader.getSumTotalTermFreq("text");
        
        for (int i=0;i<iterations;i++){
            
            //E-step (update relative likelihoods)
            for (String w:counts.keySet()){
                cf = (double) reader.totalTermFreq(new Term("text", w));
                ptF = (1-ssm_lambda)*counts.get(w)/mass;
                ptC = (ssm_lambda)*cf/sum_cf;
                rl = ptF/(ptF+ptC);
                rel_likelihoods.put(w, rl);
            }
            
            
            //M-step (recalculate max-likelihood of estimates given relative likelihoods)
            mass = 0.0;
            for (String w:counts.keySet()){
                co = counts.get(w);
                rl = rel_likelihoods.get(w);
                mass += co*rl;
                counts.put(w, co*rl);
            }
            
            //logger.info("iter " + i + "\t"  + mass + " ");
            
        }
        
        //normalise partial count vector by updated mass and return
        
        for (String w: counts.keySet()){
            counts.put(w, counts.get(w)/mass);
        }
        
        
        return counts;
        
    }    

    /**
     * What is the likelihood of the Query Model given the original query
     * 
     * @param reader
     * @param query
     * @param e_terms
     * @return
     * @throws IOException 
     */
    /*
    private double QueryModelLikelihood(IndexReader reader,CustomQuery query, Map<String,Double> e_terms) throws IOException{
        
        double prob=0.0;
        
        for (String term:query.keySet()){
            
            Double p = e_terms.get(term);
            
            
            if (p == null){
                p = 0.5;
            }
            
            prob = prob * p;
            
        }
        return prob;
        
    }
    */
    
    
}
