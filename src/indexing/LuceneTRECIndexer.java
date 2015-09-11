
package indexing;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import logging.LoggerInitializer;
import org.apache.commons.math3.special.Gamma;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.*;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Node;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Whitelist;
import utils.UncompressInputStream;
import utils.Utils;

/**
 * 
 * Index lucene documents for use with the SPUD model
 * 
 *
 * @author ronanc
 */
public class LuceneTRECIndexer {

    private final static Logger logger = Logger.getLogger( LuceneTRECIndexer.class.getName());
    
    
    public static int zipped = 1;
    public static int stemmed = 1;
    public static String stopwordfile;
    public static int doc_types;
    
    //just the inverted index
    private final IndexWriter writer;
    
    //holds the actual document text (stored field)
    private final IndexWriter documents_writer;
    
    private final Analyzer analyzer;
    private int docs=0;
    
    public LuceneTRECIndexer(String location) throws IOException{

        Path p = Paths.get(location);
        
        Path p2 = Paths.get(location + "_documents");
        if (stemmed == 1){
            analyzer = new EnglishAnalyzer();
        }else{
            analyzer = new StandardAnalyzer();
        }
        Directory dir = new NIOFSDirectory(p);
        Directory documents_dir = new NIOFSDirectory(p2);
        
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        writer = new IndexWriter(dir, config);

        IndexWriterConfig documents_config = new IndexWriterConfig(analyzer);
        documents_writer = new IndexWriter(documents_dir, documents_config);
        
    }
    
    /**
     * return the mass (A) of 
     * the maximum entropy DCM 
     * given one sample (in a map)
     * 
     * (need to make faster) 
     * This is just a simple iterative method (not used for now)
     * 
     * @param doc
     * @param length
     * @return 
     */
    
    private double maxEntropyMass(TreeMap<String, Integer> doc, double length){
        
        double ent,sum,a;
        double prev_ent = Double.NEGATIVE_INFINITY;
        
        boolean flag = false;
        if (doc.size() == 0){
            return 0.0;
        }
        double mass=doc.size();
        
        for (;mass<(length);mass+= (int)(Math.ceil((length-mass)/30))){
            sum = 0.0;
            ent = 0.0;
            for (String term:doc.keySet()){
                a = mass * doc.get(term)/length;
                sum +=  (a-1.0)*Gamma.digamma(a);
            }
            ent = (logB(doc,length,mass)) + (mass - doc.size())*Gamma.digamma(mass) - sum;
            
            
            
            if ((ent - prev_ent)<0){
                flag = true;
                break;
            }
            
            prev_ent = ent;
        }
        
        //logger.info(mass + "\t" +  doc.size() + "\t" + length + "\t" + mass/length + "\t" + doc.size()/mass);
        
        if (!flag){
            //logger.info("Warning MaxEnt not found!! " + doc.size());
            mass = doc.size();
        }
        return mass;
    }
    
    
    /**
     * get the log of the Dirichlet norm factor
     * 
     * @param doc
     * @param length
     * @param mass
     * @return 
     */
    private double logB(TreeMap<String, Integer> doc, double length, double mass){
        Integer f;
        double sumlog=0.0;
        for (String term:doc.keySet()){
            f = doc.get(term);
            sumlog += Gamma.logGamma(mass*(f/length));
            
        }
        
        sumlog = sumlog - Gamma.logGamma(mass);
        
        //logger.info("logB " + sumlog);
        return sumlog;
    }
    
    /**
     * We calculate the TotalTerms and UniqueTerms of a document
     * before indexing and store these stats in the index as stored fields
     * (as well as in the index for use during ranking).
     * 
     * This could be optimsed further. 
     * 
     * @param text
     * @param doc_id
     * @throws IOException 
     */
    
    private void addDoc(String text, String doc_id) throws IOException {
        Document doc = new Document();
        Document stored_doc = new Document();
        TreeMap<String, Integer> map = new TreeMap();
        int length = 0;
        Integer freq;
        
        TokenStream ts = analyzer.tokenStream("myfield", new StringReader(text));
        OffsetAttribute offsetAtt = ts.addAttribute(OffsetAttribute.class);
        try {
            ts.reset(); // Resets this stream to the beginning. (Required)
            while (ts.incrementToken()) {
                //index terms greater than length 1
                if (ts.getAttribute(CharTermAttribute.class).toString().length() > 1){
                    freq = map.get(ts.getAttribute(CharTermAttribute.class).toString());
                    if (freq == null){
                        freq = 0;
                    }
                    freq++;
                    map.put(ts.getAttribute(CharTermAttribute.class).toString(), freq);
                    
                    //logger.info(ts.getAttribute(CharTermAttribute.class) + " " + freq);
                    //logger.info(text.substring(offsetAtt.startOffset(), offsetAtt.endOffset()));
                    length++;
                }
            }
            ts.end();   // Perform end-of-stream operations, e.g. set the final offset.
        } finally {
            ts.close(); // Release resources associated with this stream.
        }        

        double entropy=0,p;
        for (String term: map.keySet()){
            p = ((double)map.get(term)/(double)length);
            entropy -= p * Math.log(p);
        }
        
        Long entropy_power = new Double(Math.exp(entropy)).longValue();
        
        //Long max_entropy = new Double(maxEntropyMass(map,length)).longValue();
        
        //logger.info(Math.exp(entropy) + "\t" + max_entropy + "\t" + map.size());
        //logger.info(entropy_power + " " + map.size());        
        

        //index options 
        FieldType vec_field = new FieldType();
        vec_field.setIndexOptions(vec_field.indexOptions().DOCS_AND_FREQS_AND_POSITIONS);
        
        //if you wish to use the query expansion techniques in the code, 
        //you will need to store the document (i.e. vec_field.setStored(true);)
        //
        //Unfortunately, storing long fields in the index slows down retrieval considerably
        //This is simply an artefact of how lucene works and need not be the case.  
        //Retrieval is quite fast when the actual text is not stored. A way to overcome this 
        //problem is to store the actual text in a different lucene database 
        //and only do a direct lookup of the stored field in the different database 
        //when needed. This is what I do here with "writer" and "document_writer"
        //
        vec_field.setStored(false);
        vec_field.setStoreTermVectors(false);
        vec_field.setOmitNorms(false);
        
        //important to store document length heres (both UniqueTerms and TotalTerms)
        doc.add(new StringField("doc_id", doc_id, Field.Store.YES));
        doc.add(new NumericDocValuesField("UniqueTerms", map.size()));
        doc.add(new NumericDocValuesField("TotalTerms", length));
        doc.add(new NumericDocValuesField("Entropy", entropy_power));
        //doc.add(new NumericDocValuesField("MaxEntropy", max_entropy));
        doc.add(new StoredField("UniqueTerms", map.size()));
        doc.add(new StoredField("TotalTerms", length));
        doc.add(new StoredField("Entropy", entropy_power));
        //doc.add(new StoredField("MaxEntropy", max_entropy));
        doc.add(new Field("text", text, vec_field));
    
        
        //logger.info(length + "\t" + map.size());
        writer.addDocument(doc);


        //store the doc_id in a StringField and the text in a different index
        stored_doc.add(new StringField("doc_id", doc_id, Field.Store.YES));
        stored_doc.add(new StoredField("text",text));
        
        this.documents_writer.addDocument(stored_doc);
        
        //logger.info(doc_id +"\n" + text);
        docs++;
        if ((docs %10000)==0){
            logger.info(docs + " documents indexed ...");
        }
        
    }

    
    
    private void indexTrec(String files) throws FileNotFoundException, IOException{
        
        BufferedReader br = new BufferedReader(new FileReader(files));
        String line;
        while ((line = br.readLine()) != null) {
            
            try{
                if (LuceneTRECIndexer.doc_types == 1){
                    parseNewsFile(line);
                }else if (LuceneTRECIndexer.doc_types == 2){
                    parseTRECWebFile(line);
                }else if (LuceneTRECIndexer.doc_types == 3){
                    parseUkwacFile(line);
                }else if (LuceneTRECIndexer.doc_types == 4){
                    this.parseWikiFile(line);
                }else if (LuceneTRECIndexer.doc_types == 5){
                    this.parseOHSUFile(line);
                }else if (LuceneTRECIndexer.doc_types == 6){
                    this.parseTRECXMLFile(line);
                }
                
            }catch(Exception e){
                logger.info("Could not index file " + line + ". Message is " + e.toString());
                e.printStackTrace();
            }
            
        }
        
    }
    
    
    
    
    
    /*
    * Quick function to index TREC News format
    *
    */
    private void parseNewsFile(String filename) throws IOException{
        
        
        
        BufferedReader br;
        
        if (LuceneTRECIndexer.zipped == 1) {
            //logger.log(Level.INFO, "Zipped");
            InputStream fileStream = new FileInputStream(filename);
            //InputStream gzipStream = new GZIPInputStream(fileStream);
            InputStream gzipStream =new UncompressInputStream(new FileInputStream(filename));
            //Reader decoder = new InputStreamReader(gzipStream, "UTF-8");
            Reader decoder = new InputStreamReader(gzipStream);
            br = new BufferedReader(decoder);
        } else {
            br = new BufferedReader(new FileReader(filename));
        }
        
        String[] terms;
        String doc_id =null;
        boolean index=false;
        StringBuilder doc = null;
        
        String line;
        while ((line = br.readLine()) != null) {
            
            
            
            if (line.startsWith("<!--")){
                continue;            
            }
            
            if (line.startsWith("</DOC>")){
                index = false;
                addDoc(doc.toString(), doc_id);
                doc=null;
            }
            
            if (line.contains("<DOC>")){
                doc = new StringBuilder();
            }
        
            
            if (line.contains("<DOCNO>")){
                StringBuilder sb = new StringBuilder();
                sb.append(line);
                
                if (!line.contains("</DOCNO>")){
                    
                    while (!(line = br.readLine()).contains("</DOCNO>")){
                        sb.append(line);
                    }
                    sb.append(line);
                    index = true;
                }
                //logger.log(Level.INFO, sb.toString());
                doc_id = sb.toString().substring(7, sb.lastIndexOf("</DOCNO>"));
                doc_id = Utils.strip_whitespace(doc_id);
                //local_index.indexTerm(doc_id, " ");
                //logger.log(Level.INFO, doc_id);
                
                
            }
             
            terms = line.split(" ");
            //logger.log(Level.INFO, line);
            String stem;
            
            for (String word:terms){
                
                
                if (index){
                    //logger.log(Level.INFO, "Before|" + doc_id + "|" + word + "|");
                    doc_id = Utils.strip_whitespace(doc_id);
                    //logger.log(Level.INFO, "Indexing" + doc_id + "\t"+ word);
                    if (!((word.contains("<") && word.contains("<")))){
                        doc.append(word).append(" ");
                    }
                }else{
                    //logger.log(Level.INFO, "Not indexing" + doc_id + "\t"+ word);
                }
                                
            }
            
            
            /**
             * turn on for next term
             */
            if ((line.contains("<HEADLINE>"))||(line.contains("<TEXT>"))){
                index = true;
            }            
            
        }
        
        

        br.close();
        
    }

    
    /**
     * Index a TREC format HTML page
     * 
     * @param filename
     * @throws Exception 
     */
    private synchronized void parseTRECWebFile(String filename) throws Exception{
        
        
        //logger.info(" Indexing " + filename);
        
        
        BufferedReader br;
        StringBuilder html = null;
        
        if (LuceneTRECIndexer.zipped == 1) {
            //logger.log(Level.INFO, "Zipped");
            InputStream fileStream = new FileInputStream(filename);
            InputStream gzipStream = new GZIPInputStream(fileStream);
            //InputStream gzipStream =new UncompressInputStream(new FileInputStream(filename));
            Reader decoder = new InputStreamReader(gzipStream, "UTF-8");
            //Reader decoder = new InputStreamReader(gzipStream);
            br = new BufferedReader(decoder);
        } else {
            //logger.log(Level.INFO, "Not Zipped");
            br = new BufferedReader(new FileReader(filename));
        }        
        
        String line;
        String[] terms;
        String doc_id =null;
        boolean index=false;
        
        while ((line = br.readLine()) != null) {
            
            /**
             * turn off for next term
             */
            if (line.startsWith("</DOC>")){
                //logger.log(Level.INFO, convert(html.toString()));
                
                if (html != null) {   
                    
                    String d = convert(html.toString());
                    //logger.info(d);
                    addDoc(d, doc_id);
                }
                index = false;
            }             
 
        
            StringBuilder sb = new StringBuilder();
            if (line.startsWith("<DOCNO>")){
                
                sb.append(line);
                
                if (!line.contains("</DOCNO>")){
                    
                    while (!(line = br.readLine()).contains("</DOCNO>")){
                        sb.append(line);
                    }
                    sb.append(line);
                }
                //logger.log(Level.INFO, sb.toString());
                doc_id = sb.toString().substring(7, sb.lastIndexOf("</DOCNO>"));
                doc_id = Utils.strip_whitespace(doc_id);
                
                
            }
            
            terms = line.split(" ");
            //logger.log(Level.INFO, line);
            String stem;
            
            for (String word:terms){
                
                if (index){

                    html.append(word).append(" ");
                    //logger.log(Level.INFO, "Indexing" + doc_id + "\t"+ word);
                }else{
                    //logger.log(Level.INFO, "Not indexing" + doc_id + "\t"+ word);
                }
                                
            } 
            
            
            /**
             * turn on for next term
             */
            if (line.contains("</DOCHDR>")){ 
                index = true;
                html = new StringBuilder();
            }            
            
            
            
        }
        
        br.close();
        
    }
        
    private void parseUkwacFile(String filename) throws IOException{
        
        
        
        BufferedReader br;
        
        if (LuceneTRECIndexer.zipped == 1) {
            //logger.log(Level.INFO, "Zipped");
            InputStream fileStream = new FileInputStream(filename);
            InputStream gzipStream = new GZIPInputStream(fileStream);
            //InputStream gzipStream =new UncompressInputStream(new FileInputStream(filename));
            //Reader decoder = new InputStreamReader(gzipStream, "UTF-8");
            Reader decoder = new InputStreamReader(gzipStream);
            br = new BufferedReader(decoder);
        } else {
            br = new BufferedReader(new FileReader(filename));
        }
        
        String[] terms;
        String doc_id =null;
        StringBuilder doc = null;
        
        String line;
        while ((line = br.readLine()) != null) {
            
 
        
            StringBuilder sb = new StringBuilder();
            if (line.startsWith("<text n=")){
                
                doc = new StringBuilder();
                
                doc_id = line.split(" ")[1];
                //logger.log(Level.INFO, sb.toString());
                doc_id = doc_id.substring(3, doc_id.length()-1);
                
                
            }else{
            
                terms = line.split("[ /]");
            
                
                for (String word : terms) {

                    if (word.startsWith("lem=") && (word.length() > 5)) {
                        word = word.substring(5, word.length() - 1);
                        //logger.info("Indexing " + doc_id + "\t"+ word);
                        doc_id = Utils.strip_whitespace(doc_id);
                        //word = Utils.tidyWord(word);
                        if ((!word.isEmpty())&&(!word.equals("apos"))&&(!word.equals("amp"))&&(!word.equals("quot"))) {
                            doc.append(word).append(" ");
                        }
                    }
                }
                
            }
            
            
            if (line.startsWith("</text>")){
                //logger.info(doc.toString());
                this.addDoc(doc.toString(), doc_id);
            }
            
        }
        
        

        br.close();
        
    }    
    
    
    private void parseWikiFile(String filename) throws IOException{
        
        
        
        BufferedReader br;
        
        if (LuceneTRECIndexer.zipped == 1) {
            //logger.log(Level.INFO, "Zipped");
            InputStream fileStream = new FileInputStream(filename);
            InputStream gzipStream = new GZIPInputStream(fileStream);
            //InputStream gzipStream =new UncompressInputStream(new FileInputStream(filename));
            //Reader decoder = new InputStreamReader(gzipStream, "UTF-8");
            Reader decoder = new InputStreamReader(gzipStream);
            br = new BufferedReader(decoder);
        } else {
            br = new BufferedReader(new FileReader(filename));
        }
        
        String[] terms;
        String doc_id =null;
        StringBuilder doc = null;
        
        String line;
        String title;
        while ((line = br.readLine()) != null) {
         
 
        
            
            if (line.startsWith("<doc id")){
                doc = new StringBuilder();
                
                String [] toks = line.split(" ");
                //logger.info(Arrays.toString(toks));
                doc_id = toks[1];
                doc_id = doc_id.substring(4, doc_id.length()-1);
                
                //logger.info(doc_id);
                
                title = toks[toks.length-1];
                String[] titles = title.split("=");
                
                if (titles.length>1){
                    //logger.info(titles[1]);
                    
                    String[] title_words = titles[1].split(" ");
                    for (String w : title_words){
                        w = Utils.tidyWord(w);
                        doc.append(w).append(" ");
                    }
                    
                }
                
                
                
            }else{
            
                //logger.info(line);
                terms = line.split("[ /]");

                for (String word : terms) {

                    //doc_id = Utils.strip_whitespace(doc_id);
                    word = Utils.tidyWord(word);
                    if (!word.isEmpty()) {
                        doc.append(word).append(" ");
                    }
                }
            }

           
            
            if (line.startsWith("</doc>")){
                //logger.info(doc_id);
                //logger.info(doc.toString());
                this.addDoc(doc.toString(), doc_id);
            }
            
            
        }
        br.close();
        
    }     
    
    
    
    
    
    private void parseOHSUFile(String filename) throws IOException{
        
        BufferedReader br;
        
        if (LuceneTRECIndexer.zipped == 1) {
            //logger.log(Level.INFO, "Zipped");
            InputStream fileStream = new FileInputStream(filename);
            //InputStream gzipStream = new GZIPInputStream(fileStream);
            InputStream gzipStream =new UncompressInputStream(new FileInputStream(filename));
            //Reader decoder = new InputStreamReader(gzipStream, "UTF-8");
            Reader decoder = new InputStreamReader(gzipStream);
            br = new BufferedReader(decoder);
        } else {
            br = new BufferedReader(new FileReader(filename));
        }
        
        String[] terms;
        String doc_id =null;
        boolean index=false;
        StringBuilder doc = null;
        
        String line;
        while ((line = br.readLine()) != null) {
            
            
            /**
             * turn off for next term
             */
            if (line.startsWith(".I")){
                doc = new StringBuilder();
                index = false;
            }             
 
        
            StringBuilder sb = new StringBuilder();
            if (line.startsWith(".U")){
                
                sb.append(br.readLine());
                //logger.log(Level.INFO, sb.toString());
                doc_id = sb.toString();
                doc_id = Utils.strip_whitespace(doc_id);
                //logger.log(Level.INFO, doc_id);

                
                
                line = br.readLine();
            }
            
            
            
            terms = line.split("[ /]");
            //logger.log(Level.INFO, line);
            String stem;
            
            for (String word:terms){
                
                if (index){
                    word = Utils.tidyWord(word);
                    if (word.length() > 1){
                        doc_id = Utils.strip_whitespace(doc_id);
                        //logger.log(Level.INFO, "|" + doc_id + "|" + word + "|");
                        doc.append(word).append(" ");
                        //local_index.indexTerm(doc_id, word);
                    }
                    //logger.log(Level.INFO, "Indexing" + doc_id + "\t"+ word);
                }
            }
            
            
            /**
             * turn on for next term
             */
            if (line.startsWith(".S")){
                index = true;
            }  
            
            if (line.startsWith(".A")){
                this.addDoc(doc.toString(), doc_id);
            }
            
        }
        
        
        br.close();
        
    }
    

    /**
     * Index a TREC format XML HTML page
     * 
     * @param filename
     * @throws Exception 
     */
    private synchronized void parseTRECXMLFile(String filename) throws Exception{
        
        
        //logger.info(" Indexing " + filename);
        
        
        BufferedReader br;
        
        
        if (LuceneTRECIndexer.zipped == 1) {
            //logger.log(Level.INFO, "Zipped");
            InputStream fileStream = new FileInputStream(filename);
            InputStream gzipStream = new GZIPInputStream(fileStream);
            //InputStream gzipStream =new UncompressInputStream(new FileInputStream(filename));
            Reader decoder = new InputStreamReader(gzipStream, "UTF-8");
            //Reader decoder = new InputStreamReader(gzipStream);
            br = new BufferedReader(decoder);
        } else {
            //logger.log(Level.INFO, "Not Zipped");
            br = new BufferedReader(new FileReader(filename));
        }        
        
        String line;
        String[] terms;
        String doc_id =null;
        String text;
        
        String start_delim = "<article-id pub-id-type=\"pmc\">";
        String end_delim = "</article-id>";
        StringBuilder sb = new StringBuilder();
        while ((line = br.readLine()) != null) {

            sb.append(line);

            /**
             * get the id
             */
            if (line.contains(start_delim)) {
                int start = line.indexOf(start_delim) + start_delim.length() ;
                int end = line.indexOf(end_delim, start);
                doc_id = line.substring(start, end);

                //logger.info("id " + doc_id);
            }
            
            
            
            

        }

        
        text = convert(sb.toString().replace("><", "> <"));
            
        //logger.info(doc_id + "\n" + text);
          
        addDoc(text, doc_id);    
            
         
       
        
        br.close();
        
    }
    
    
    
    
    /**
     * uses jJSOUP
     * @param html
     * @return 
     */
    private synchronized String convert(String html) {

        org.jsoup.nodes.Document doc = Jsoup.parse(html);
        //Document doc = Jsoup.parse(html);
        removeComments(doc);
        doc = new Cleaner(Whitelist.relaxed()).clean(doc);
        
        String str = doc.text();

        str = str.replaceAll("/", " ");
        str = str.replaceAll("\n", " ");

        return str;
    }

    private synchronized static void removeComments(Node node) {
        for (int i = 0; i < node.childNodes().size();) {
            Node child = node.childNode(i);
            if (child.nodeName().equals("#comment")) {
                child.remove();
            } else {
                removeComments(child);
                i++;
            }
        }
    }   
    
  
    
    
    
    public void close() throws IOException {
        this.writer.close();
        this.documents_writer.close();
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {

        LoggerInitializer.setup();

        if (args.length > 4) {

            String trec_files = args[1];
            
            LuceneTRECIndexer.doc_types = Integer.parseInt(args[2]);
            LuceneTRECIndexer.zipped = Integer.parseInt(args[3]);
            LuceneTRECIndexer.stemmed = Integer.parseInt(args[4]);
            //LuceneTRECIndexer.stopwordfile = "/home/rc635/Dropbox/Code/lucene/aux/stopwords.english.large";
            LuceneTRECIndexer indexer = new LuceneTRECIndexer(args[0]);
            indexer.indexTrec(trec_files);
            

            indexer.close();
        }else{
            logger.info("LuceneTRECIndexer (directory) (indexfile) (1=TRECNews, 2=TRECWeb, 3=UKWac, 4=Wikipedia, 5=OHSU, 6=PubMed) (zipped=1, else 0) (1=stemmed, else 0)\n \n "
                    + "\t\"directory\" will be where the lucene index is created,\n"
                    + "\t\"indexfile\" is a file listing the full paths of the trec files to be indexed,\n"
                    + "\t\"1=News, 2=Web\" determines whether the trec files are in html format or not,\n"
                    + "\t\"zipped=1, else 0\" for zipped or plain-text."
                    + "\n\n\n\tNote: This indexer indexes two numeric fields into each document "
                    + "for document normalisation used during ranking.\n");
        }
    }

    
    
}
