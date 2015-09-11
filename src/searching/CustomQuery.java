package searching;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.similarities.SPUDLMSimilarity;


/**
 *
 * @author ronanc
 */
public class CustomQuery {
    
    private final static Logger logger = Logger.getLogger(CustomQuery.class.getName());  
    
    private TreeMap<String,Double> bag;

    

    private Double mass;
    
    
    
    
    public CustomQuery(){
        bag = new TreeMap<String,Double>();
        mass = 0.0;

    }
    
    
    public void empty(){
        bag.clear();
        mass = 0.0;
    }
    
    
    public void add(String str, Double f){
        
        Double c = bag.get(str);
        
        
        if (c==null){
            bag.put(str, f);
        }else{
            bag.put(str, c+f);
        }
        mass += f;
    }
    
    
    
    public void remove(String str, Double f){
        Double c = bag.get(str);
        
        if (c==null){
            bag.put(str, -f);
        }else{
            bag.put(str, c-f);
        }
        mass -= f;
    }
    
   
    
    
    public double get(String str){
        
        Double ret = bag.get(str);
        
        if (ret == null){
            return 0;
        }else{
            return ret;
        }
        
    }
    
    //types in bag
    public int numTypes(){
        return this.bag.size();
    }
    
    //raw mass of query bag
    public double mass(){
        return this.mass;
    }
    
    

    
    //
    // returns the string representation
    public String toString(){
        StringBuilder sb = new StringBuilder();
        for (String key: this.bag.keySet()){
            sb.append(key).append(" ");
        }
        return sb.toString();
    }
    
    
    public boolean contains(String term){
        return this.bag.containsKey(term);
    }
    
    public String examine(){
        ArrayList<Map.Entry<String, Double>> l = QueryExpansion.sortValue(bag);
        return "Mass: " + this.mass + " " + l.toString();
    }    
    

    
    
    
    
    
}
