
package searching;


import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 *
 * @author ronanc
 */
public class Evaluator {
    
    private final static Logger logger = Logger.getLogger(Evaluator.class.getName());    
    
    public static class RankedList{
        
        String doc_id;
        Float score;
        
    }
    
    
    private final HashMap<String,HashMap<String,String>> qrels;

    
    
    /**
     * load qrels file
     * @param qrels_file
     * @throws FileNotFoundException
     * @throws IOException 
     */
    public Evaluator(String qrels_file) throws FileNotFoundException, IOException {

        qrels = new HashMap();

        BufferedReader br = new BufferedReader(new FileReader(qrels_file));
        String line;
        String [] toks;
        String doc_id, rel;
        String q_num;
        HashMap map;
                
        while ((line = br.readLine()) != null) {
            
            
            if (qrels_file.contains("ohsu")){
                toks = line.split("\\s+");
                q_num = toks[0];
                doc_id = toks[1];
                rel = toks[2];                
            }else{
                toks = line.split("\\s+");
                q_num = toks[0];
                doc_id = toks[2];
                rel = toks[3];
                
            }
            
            if (!qrels.containsKey(q_num)){
                qrels.put(q_num, new HashMap<String,String>());
            }
            
            map = qrels.get(q_num);
            
            
            //only put relevant document in map
            if (!rel.equals("0")){
                
                //logger.log(Level.INFO, "put " + doc_id + " " + rel);
                map.put(doc_id, rel);
                //logger.log(Level.INFO, q_num + " " + doc_id + " " + rel);
            }
            
            
        }
        
        
        br.close();

    }
    
    
    





    public double AP(String qnum, RankedList[] ranked_list){
        
        double ap = 0.0;
        int found = 0;
        
        HashMap<String,String> rels = qrels.get(qnum);

        if (rels == null){
            //logger.info(qnum + " does not have relevance judgements ?" );
            return Double.NaN;
        }
        for(int i=0;i<ranked_list.length;i++){
            //logger.log(Level.INFO, "checking " + i + " " + ranked_list[i].doc_id + " " + ranked_list.length);
            //if relevant
            if (rels.containsKey(ranked_list[i].doc_id)){
                found++;
                //logger.log(Level.INFO, "found " + ranked_list[i].doc_id);
                ap += (double)found/(double)(i+1);
            }
            
        }
        //logger.log(Level.INFO, "# of qrels " + rels.size());
        ap = ap / (double)rels.size();
        
        return ap;
    }
    
    

    /**
     * NDCG for binary judgements as found in 
     * Chris Burges, Tal Shaked, Erin Renshaw, Ari Lazier, Matt Deeds, Nicole Hamilton, 
     * and Greg Hullender. 2005. Learning to rank using gradient descent. In Proceedings of the 
     * 22nd international conference on Machine learning (ICML '05). ACM, New York, NY, USA, 89-96. 
     * DOI=10.1145/1102351.1102363 http://doi.acm.org/10.1145/1102351.1102363
     * 
     * @param qnum
     * @param ranked_list
     * @param res_len
     * @return 
     */
    
    public double NDCG(String qnum, RankedList[] ranked_list, int res_len){
        
        double dcg = 0.0, ndcg;
        double n = 0.0;
        int found = 0;
        HashMap<String,String> rels = qrels.get(qnum);
        
        if (rels == null){
            //logger.info(qnum + " does not have relevance judgements ?" );
            return Double.NaN;
        }        
        
        for(int i=0;((i<ranked_list.length) && (i<res_len) && (found < rels.size()));i++){
            
            
            
            //if relevant
            if (rels.containsKey(ranked_list[i].doc_id)){
                found++;
                dcg += (double)1.0/(double)(Math.log(i+2.0)/Math.log(2.0)); 
            }
            
        }

        //normalisation
        for (int i = 0; ((i<res_len) && (i < rels.size())); i++) {
            n += (double) 1.0 / (double) (Math.log(i + 2.0) / Math.log(2.0));
            
        }
        
        ndcg = dcg/n;
        
        return ndcg;
        
        
    }
    
    
    
    public double prec(String qnum, RankedList[] ranked_list, int res_len){
        
        double p = 0.0;
        int found = 0;
        
        HashMap<String,String> rels = qrels.get(qnum);

        if (rels == null){
            return Double.NaN;
        }
        
       
        
        for(int i=0;(i<res_len)&&(i<ranked_list.length);i++){
            if (rels.containsKey(ranked_list[i].doc_id)){
                found++;
            }
            
        }
        p = (double)found / res_len;
        
        return p;
    }    
    
    public Map getQrels(){
        return this.qrels;
    }
    
    
    public int numQrels(String qnum){
        return this.qrels.get(qnum).size();
    }    
    
    public double RECALL(String[] ranked_list){
        return 0.0;
    }
    
    public double NDCG20(String qnum, RankedList[] ranked_list){
        return NDCG(qnum, ranked_list, 20);
    }

    public double NDCG10(String qnum, RankedList[] ranked_list){
        return NDCG(qnum, ranked_list, 10);
    }
    
    
}
