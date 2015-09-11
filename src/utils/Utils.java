package utils;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 *
 * @author ronanc
 */
public class Utils {

    private final static Logger logger = Logger.getLogger(Utils.class.getName());

    
    
    public static String applyAnalyzer(String text, Analyzer analyzer) throws IOException{
        String term;
        StringBuilder sb = new StringBuilder();
        
        TokenStream ts = analyzer.tokenStream("myfield", new StringReader(text));
        //OffsetAttribute offsetAtt = ts.addAttribute(OffsetAttribute.class);
        try {
            ts.reset(); // Resets this stream to the beginning. (Required)
            while (ts.incrementToken()) {
                term = ts.getAttribute(CharTermAttribute.class).toString();
                sb.append(term).append(" ");
            }
            ts.end();
        } finally {
            ts.close();
        }         
        
        
        return sb.toString();
    }    
    
    public static float log2(float x){
        return (float) (Math.log(x)/Math.log(2.0));
    }
    
    
    public synchronized static String tidyWord(String str){
       
        if(str.matches("[-]+")){
            return "";
        }else if(str.length() > 20){
            return "";
        }else if( str.startsWith("<") && str.endsWith(">")){
            return "";
        }else {
            
            //to lower case
            str = str.toLowerCase();

            //replace any non word characters
            str = str.replaceAll("[^a-zA-Z0-9- ]", "");

            

            return str;
        }
        
    }    
    
    
    
    public static String strip_whitespace(String word){
        word = word.replaceAll("[^a-zA-Z0-9-]", "");
        return word;
    }
    

   
}
