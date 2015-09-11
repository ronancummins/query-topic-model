package searching;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import logging.LoggerInitializer;
import org.apache.commons.math3.special.Gamma;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DocumentStoredFieldVisitor;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.LMDirichletSimilarity;
import org.apache.lucene.search.similarities.SPUDLMSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.BytesRef;
import searching.Evaluator.RankedList;
import utils.Utils;

/**
 *
 * @author ronanc
 */
public class QuerySearch {
    

    
    private final static Logger logger = Logger.getLogger( QuerySearch.class.getName());
 
    private final IndexSearcher searcher;
    private final IndexReader reader;
    private final Analyzer analyzer;

    //for reading actual documents for expansion
    private IndexSearcher documents_searcher;
    private IndexReader documents_reader;
    
    
    public static final int title = 1;
    public static final int title_desc_narr = 2;
    public static final int desc_narr = 3;
    public static final int desc = 4;
    public static final int narr = 5;
    
    public static int stemmed = 1;
    

    private final HashMap<String, String[]> title_queries;
    private final HashMap<String, String[]> title_desc_narr_queries;
    private final HashMap<String, String[]> desc_queries;
    private final HashMap<String, String[]> narr_queries;
    private final HashMap<String, String[]> desc_narr_queries;

    
    
    private final TreeMap<String,Double> AP_values;
    private final TreeMap<String,Double> PREC_values;
    private final TreeMap<String,Double> NDCG10_values;
    private final TreeMap<String,Double> NDCG20_values;
    
    
    public static int query_type;
    
    private Evaluator eval;
    
    private HashMap<String, String[]> current_set;
    
    private int max_iterations = 10;
    
    public static CustomQuery currentQuery;
    
    public static Set<String> loadfields;
    
    public static int max_qlen = 1000;
    
    
    public static double N;
    public static double sum_cf;
    public static double sum_df;
    
    public QuerySearch(String location) throws IOException{

        
        Path p = Paths.get(location);
        
        Path p2 = Paths.get(location + "_documents");
        
        Directory dir = new NIOFSDirectory(p);
        Directory documents_dir = new NIOFSDirectory(p2);
        
        reader = DirectoryReader.open(dir);
        searcher = new IndexSearcher(reader);
        
        documents_reader = DirectoryReader.open(documents_dir);
        documents_searcher = new IndexSearcher(documents_reader);
        
        N = reader.getDocCount("text");
        sum_cf = reader.getSumTotalTermFreq("text");
        sum_df = reader.getSumDocFreq("text");
        
        if (QuerySearch.stemmed == 1){
            logger.info("English Analyzer with stemming");
            analyzer = new EnglishAnalyzer();
        }else{
            logger.info("Standard Analyzer without stemming");
            analyzer = new StandardAnalyzer();
        }
        
        title_queries = new HashMap();
        desc_queries = new HashMap();
        narr_queries = new HashMap();        
        title_desc_narr_queries = new HashMap();
        desc_narr_queries = new HashMap();
        
        AP_values = new TreeMap<>();
        PREC_values = new TreeMap<>();
        NDCG10_values = new TreeMap<>();
        NDCG20_values = new TreeMap<>();
               
        currentQuery = new CustomQuery();
        
        loadfields = new TreeSet();
        loadfields.add("UniqueTerms");
        loadfields.add("TotalTerms");
        loadfields.add("doc_id");
    }
    
    
    private void clearMaps(){
        this.AP_values.clear();
        this.NDCG10_values.clear();
        this.NDCG20_values.clear();
        this.PREC_values.clear();
    }
    
    
    /**
     * 
     * This is called if SPUDLMSimilarity.b0Set is false
     * Estimates the background DCM mass
     * @return
     * @throws IOException 
     */
    
    public double estimateB0() throws IOException {

        
        // now estimate mass of background model (DCM)
        //
        logger.info("estimate background DCM mass...");
        double denom;
        double s = 250;
        double sumDocFreq = reader.getSumDocFreq("text");
        double numDocs = reader.getDocCount("text");
        for (int i = 0; i < max_iterations; i++) {
            logger.log(Level.INFO, "iteration " + i + " estimated mu value is " + s );
            denom = 0;
            
            for (int j = 0; j < reader.maxDoc(); j++) {
                
                //Document doc = reader.document(j, loadfields);
                DocumentStoredFieldVisitor visitor = new DocumentStoredFieldVisitor(QuerySearch.loadfields);
                reader.document(j, visitor);
                //Document doc = reader.document(j);
                Document doc = visitor.getDocument();
                String[] str_dl =  doc.getValues("TotalTerms");
                //logger.info(doc.get("text").toString());
                //logger.info(str_dl[0] + "");
                Double dl = Double.parseDouble(str_dl[0]);
                denom += Gamma.digamma(s + dl);
            }
            
            
            denom =  (denom - (numDocs * Gamma.digamma(s)));
            
            s = sumDocFreq/denom;

        }        
        logger.info("done.");
        
        
        
        return s;
    }
    
    
    
    /**
     * prels is not implemented here. 
     * Just use qrels
     * @param _qrels
     * @throws IOException 
     */
    public void loadQrels(String _qrels) throws IOException{
        eval = new Evaluator(_qrels);
    }
    
    /**
     * 
     * This just loads queries, for iterating through later
     * 
     * @param fname
     * @throws FileNotFoundException
     * @throws IOException 
     */
    
    public void loadTopics(String fname) throws FileNotFoundException, IOException {
        BufferedReader br = new BufferedReader(new FileReader(fname));
        String line;

        StringBuilder sb_title = new StringBuilder();
        StringBuilder sb_desc = new StringBuilder();
        StringBuilder sb_narr = new StringBuilder();
        StringBuilder sb_title_desc_narr = new StringBuilder();
        StringBuilder sb_desc_narr = new StringBuilder();
        String[] toks;
        String cur_topic_num = null;
        while ((line = br.readLine()) != null) {

            if (line.startsWith("<num>")) {

                //add to hash
                if (cur_topic_num != null) {
                    title_queries.put(cur_topic_num, sb_title.toString().split(" "));
                    title_desc_narr_queries.put(cur_topic_num, sb_title_desc_narr.toString().split(" "));
                    desc_queries.put(cur_topic_num, sb_desc.toString().split(" "));
                    desc_narr_queries.put(cur_topic_num, sb_desc_narr.toString().split(" "));
                    narr_queries.put(cur_topic_num, sb_narr.toString().split(" "));
                }
                cur_topic_num = line.split(" ")[2];
                //logger.log(Level.INFO, cur_topic_num);
                sb_title = new StringBuilder();
                sb_desc = new StringBuilder();
                sb_title_desc_narr = new StringBuilder();
                sb_desc_narr = new StringBuilder();
                sb_narr = new StringBuilder();
            }

            //titles
            if (line.startsWith("<title>")) {
                toks = line.split(" ");
                for (int i = 1; i < toks.length; i++) {
                    String word = Utils.tidyWord(toks[i]);

                    if ((word == null) || (word.length() < 2) || (word.equals("topic"))) {
                        continue;
                    }
                    sb_title.append(word).append(" ");
                    sb_title_desc_narr.append(word).append(" ");
                    
                }

                while (!(line = br.readLine()).startsWith("<desc>")) {
                    toks = line.split(" ");
                    for (int i = 0; i < toks.length; i++) {
                        String word = Utils.tidyWord(toks[i]);
                        if (word == null || (word.length() < 2)) {
                            continue;
                        }
                        sb_title.append(word).append(" ");
                        sb_title_desc_narr.append(word).append(" ");
                        
                    }
                }
            }

            //descs
            if (line.startsWith("<desc>")) {
                toks = line.split(" ");
                for (int i = 1; i < toks.length; i++) {

                    if (toks[i].equals("Description:")) {
                        continue;
                    }

                    String word = Utils.tidyWord(toks[i]);
                    if (word == null || (word.length() < 2)) {
                        continue;
                    }
                    sb_desc.append(word).append(" ");
                    sb_title_desc_narr.append(word).append(" ");
                    sb_desc_narr.append(word).append(" ");
                }

                if (!fname.contains("ohsu")) {
                    while (!(line = br.readLine()).startsWith("<narr>")) {
                        toks = line.split(" ");
                        for (int i = 0; i < toks.length; i++) {

                            if (toks[i].equals("Description:")) {
                                continue;
                            }

                            String word = Utils.tidyWord(toks[i]);
                            if (word == null || (word.length() < 2)) {
                                continue;
                            }
                            sb_desc.append(word).append(" ");
                            sb_title_desc_narr.append(word).append(" ");
                            sb_desc_narr.append(word).append(" ");
                        }
                    }
                } else {
                    while (!(line = br.readLine()).startsWith("</top>")) {
                        toks = line.split(" ");
                        for (int i = 0; i < toks.length; i++) {

                            if (toks[i].equals("Description:")) {
                                continue;
                            }

                            String word = Utils.tidyWord(toks[i]);
                            if (word == null || (word.length() < 2)) {
                                continue;
                            }
                            sb_desc.append(word).append(" ");
                            sb_title_desc_narr.append(word).append(" ");
                            sb_desc_narr.append(word).append(" ");
                        }
                    }

                }
            }

            if (!fname.contains("ohsu")) {

                //narr
                if (line.startsWith("<narr>")) {
                    toks = line.split(" ");
                    for (int i = 1; i < toks.length; i++) {

                        if (toks[i].equals("Narrative:")) {
                            continue;
                        }

                        String word = Utils.tidyWord(toks[i]);
                        if (word == null || (word.length() < 2)) {
                            continue;
                        }

                        sb_desc_narr.append(word).append(" ");
                        sb_narr.append(word).append(" ");
                        sb_title_desc_narr.append(word).append(" ");
                    }

                    while (!(line = br.readLine()).startsWith("</top>")) {
                        toks = line.split(" ");
                        for (int i = 0; i < toks.length; i++) {

                            if (toks[i].equals("Narrative:")) {
                                continue;
                            }

                            String word = Utils.tidyWord(toks[i]);
                            if (word == null || (word.length() < 2)) {
                                continue;
                            }

                            sb_desc_narr.append(word).append(" ");
                            sb_narr.append(word).append(" ");
                            sb_title_desc_narr.append(word).append(" ");
                        }
                    }
                }
            }
            
            

        }

        //put last query into map
        if (cur_topic_num != null) {
            title_queries.put(cur_topic_num, sb_title.toString().split(" "));
            title_desc_narr_queries.put(cur_topic_num, sb_title_desc_narr.toString().split(" "));
            desc_narr_queries.put(cur_topic_num, sb_desc_narr.toString().split(" "));
            desc_queries.put(cur_topic_num, sb_desc.toString().split(" "));
            narr_queries.put(cur_topic_num, sb_narr.toString().split(" "));
        }

        logger.log(Level.INFO, title_queries.size() + " title queries loaded ... ");
        logger.log(Level.INFO, title_desc_narr_queries.size() + " title_desc narr queries loaded ... ");
        logger.log(Level.INFO, desc_narr_queries.size() + " desc_narr queries loaded ... ");
        logger.log(Level.INFO, desc_queries.size() + " desc queries loaded ... ");
        logger.log(Level.INFO, narr_queries.size() + " narr queries loaded ... ");
        br.close();

    }

    
    
    public void setQuerySet(int type) {

        QuerySearch.query_type = type;
        if (QuerySearch.query_type == QuerySearch.title) {
            this.current_set = title_queries;
        } else if (QuerySearch.query_type == QuerySearch.title_desc_narr) {
            this.current_set = title_desc_narr_queries;
        } else if (QuerySearch.query_type == QuerySearch.desc_narr) {
            this.current_set = desc_narr_queries;
        } else if (QuerySearch.query_type == QuerySearch.desc) {
            this.current_set = desc_queries;
        } else if (QuerySearch.query_type == QuerySearch.narr) {
            this.current_set = narr_queries;
        } else {
            this.current_set = title_queries;
        }

        int avg_len = 0;
        for (String key : current_set.keySet()) {

            String[] val = current_set.get(key);
            avg_len += val.length;
            
            TreeMap<String, Integer> b = new TreeMap();
            for(String term:val){
                b.put(term, 1);
            }
            //System.out.println(val.length + "\t" + b.size());
        }

        logger.info("Average query length: " + (double) avg_len / (double) current_set.size());
    }
    
    
    
    /**
     * Get the mean of the results
     * @param map
     * @return 
     */
    public double Mean(TreeMap<String,Double> map) {
        double mean = 0.0;

        
        for(String key: map.keySet()){
            mean += (Double)map.get(key);
        }
        mean = mean / map.size();

        return mean;
    }    
    
    
    

    
    
    /**
     * Run a query 
     * @param key
     * @throws ParseException
     * @throws IOException 
     */
    
    public void runQuery(String key) throws ParseException, IOException {
        
        currentQuery.empty();
        
        //check that the query is not empty
        String[] query_array = current_set.get(key);
        if (query_array.length == 1){
            if (query_array[0].trim().equals("")){
                return;
            }
        }
        
        
        //ok
        
        StringBuilder query_str = new StringBuilder();
        for (String s : query_array) {
            query_str.append(s).append(" ");
        }
        
        
        
        int k=0;
        //custom representation of query (stemmed)
        String prep_query = Utils.applyAnalyzer(query_str.toString(), analyzer);
        for (String s: prep_query.split(" ")){
            if (k == max_qlen) break;
            currentQuery.add(s, 1.0);
            k++;
        }         
        
        //logger.info("Query: " + currentQuery.examine());
        
        
        
        Query query = new QueryParser("text", new StandardAnalyzer()).parse(currentQuery.toString());
        
        //run the SPUD dir method
        Query lmnorm_query = new LMNormQuery(query);
        searcher.setSimilarity(new SPUDLMSimilarity());
        TopDocs ret = searcher.search(lmnorm_query, 1000);
        
        //ExtractQueryTopic();
        //can run other lucene ranking functions without the lmnorm part
        //searcher.setSimilarity(new LMDirichletSimilarity());
        //searcher.setSimilarity(new BM25Similarity());
        //TopDocs ret = searcher.search(query, 1000);
        

        //could do multiple rounds of relevance expansion if (r > 1)
        for (int r = 0; r < 1; r++) {
            if (QueryExpansion.EXPAND) {
                RankedList[] orig_ranking = new RankedList[ret.scoreDocs.length];
                QueryExpansion expander = new QueryExpansion();

                for (int i = 0; i < ret.scoreDocs.length; i++) {

                    orig_ranking[i] = new RankedList();
                    orig_ranking[i].doc_id = searcher.doc(ret.scoreDocs[i].doc).get("doc_id");
                    orig_ranking[i].score = ret.scoreDocs[i].score;

                    //assume top docs are relevant and add to models
                    if (i < QueryExpansion.pseudo_rel_docs) {
                        //logger.info(orig_ranking[i].doc_id);
                        Query doc_lookup = new TermQuery(new Term("doc_id", orig_ranking[i].doc_id));
                        
                        //logger.info(q1 + "");
                        TopDocs prels = documents_searcher.search(doc_lookup,1);
                        if (prels.totalHits > 0) {
                            String orig_text = documents_searcher.doc(prels.scoreDocs[0].doc).get("text");

                            if ((QueryExpansion.method == QueryExpansion.PRM1)
                                    || (QueryExpansion.method == QueryExpansion.PRM2)) {
                                expander.addPositionalExpansionDoc(currentQuery, orig_text, orig_ranking[i].score, this.analyzer, reader);
                            } else {
                                expander.addExpansionDoc(orig_text, orig_ranking[i].score, this.analyzer, reader);
                            }
                        } else {
                            logger.warning("Expansion:: Document lookup retrieved no item in document index");
                        }
                    }

                }

                Map<String,Double> e_terms = expander.expansionTerms(this.reader, currentQuery, query_array.length);
                //logger.info(currentQuery.examine());
                
                expander.expandCustomQuery(this.reader, currentQuery, e_terms);
                //logger.info(currentQuery.examine());
                
                query = new QueryParser("text", new StandardAnalyzer()).parse(currentQuery.toString());
                lmnorm_query = new LMNormQuery(query);
                searcher.setSimilarity(new SPUDLMSimilarity());
                ret = searcher.search(lmnorm_query, 1000);
                
                
                
            }
        }
        
        
        
        
        
        // final ranking 
        // 
        
        RankedList[] ranking = new RankedList[ret.scoreDocs.length];
        
        for (int i=0;i<ret.scoreDocs.length;i++){
            ranking[i] = new RankedList();
            ranking[i].doc_id = searcher.doc(ret.scoreDocs[i].doc, QuerySearch.loadfields).get("doc_id").toString();
            ranking[i].score = ret.scoreDocs[i].score;
        }
        
        double ap = eval.AP(key, ranking);
        double ndcg10 = eval.NDCG10(key, ranking);
        double ndcg20 = eval.NDCG20(key, ranking);
        double prec10 = eval.prec(key, ranking, 10);
        
        //logger.info(ap + "\t" + ndcg10 + "\t" + ndcg20);
        if (!Double.isNaN(ap)){
            AP_values.put(key, ap);
            NDCG10_values.put(key, ndcg10);
            NDCG20_values.put(key, ndcg20);
            this.PREC_values.put(key, prec10);
            System.out.println(ap + "\t" + prec10 + "\t" + ndcg10 + "\t" + ndcg20);
        }else{
            //logger.info(key + " query does not have qrels");
        }
        
    }
    
    
    /**
     * run a set of queries
     * 
     * @throws ParseException
     * @throws IOException 
     */
    public void runQuerySet() throws ParseException, IOException{
        
        
        //wt2g b0 = 352
        //trec4_5 b0 = 240
        //gov2
        //ohsumed
        //wt10g
        
        // get to size of the inverted index 
        // if not already calculated
        
        if (SPUDLMSimilarity.b0est == true){
            SPUDLMSimilarity.b0 = estimateB0();
        }else{
            //else set to average unique terms doc length as a simple estimate
            //SPUDLMSimilarity.b0 = reader.getSumDocFreq("text")/reader.maxDoc();
            logger.info("Using estimated background model mass " + SPUDLMSimilarity.b0 );
        }

        
        logger.info("Avg Doc Vector Len " + reader.getSumDocFreq("text")/reader.maxDoc());
        logger.info("Avg Doc Len " + reader.getSumTotalTermFreq("text")/reader.maxDoc());
        
        //run the set of queries
        for (String key : this.current_set.keySet()){
            if (eval.getQrels().containsKey(key)){
                    runQuery(key);
                
            }else{
                //logger.info("No qrels for Query " + key);
            }
        }
        
        logger.log(Level.INFO, "MAP " + Mean(this.AP_values) + " P@10 " + Mean(this.PREC_values) + " NDCG10 " + 
                Mean(this.NDCG10_values) + " NDCG20 " + Mean(this.NDCG20_values) 
                + " for " + this.NDCG20_values.size() + " queries");
        
        
        clearMaps();
        System.out.println("");
    }
    
    

    /**
     * 
     * Run a query and get the related terms
     * This is a helper function for viewing 
     * topically related terms
     * 
     * 
     * @param key
     * @throws ParseException
     * @throws IOException 
     */
    
    public void getRelatedTerms(String key) throws ParseException, IOException {
        
        currentQuery.empty();
        
        String[] query_array = current_set.get(key);
        if (query_array.length == 1){
            if (query_array[0].trim().equals("")){
                return;
            }
        }
        
        
       
        
        StringBuilder query_str = new StringBuilder();
        for (String s : query_array) {
            query_str.append(s).append(" ");
        }
        
        
        //custom representation of query (stemmed)
        String prep_query = Utils.applyAnalyzer(query_str.toString(), analyzer);
        for (String s: prep_query.split(" ")){
            currentQuery.add(s, 1.0);
        }         
        
        //logger.info(key + "\t" + currentQuery.toString());
        
        
        Query query = new QueryParser("text", new StandardAnalyzer()).parse(currentQuery.toString());
        
        //run the SPUD dir method
        Query lmnorm_query = new LMNormQuery(query);
        searcher.setSimilarity(new SPUDLMSimilarity());
        TopDocs ret = searcher.search(lmnorm_query, 1000);
        
 
        
        if (QueryExpansion.EXPAND) {
            RankedList[] orig_ranking = new RankedList[ret.scoreDocs.length];
            QueryExpansion expander = new QueryExpansion();

                for (int i = 0; i < ret.scoreDocs.length; i++) {

                    orig_ranking[i] = new RankedList();
                    orig_ranking[i].doc_id = searcher.doc(ret.scoreDocs[i].doc).get("doc_id");
                    orig_ranking[i].score = ret.scoreDocs[i].score;

                    //assume top docs are relevant and add to models
                    if (i < QueryExpansion.pseudo_rel_docs) {
                        //logger.info(orig_ranking[i].doc_id);
                        Query doc_lookup = new TermQuery(new Term("doc_id", orig_ranking[i].doc_id));
                        
                        //logger.info(q1 + "");
                        TopDocs prels = documents_searcher.search(doc_lookup,1);
                        if (prels.totalHits > 0) {
                            String orig_text = documents_searcher.doc(prels.scoreDocs[0].doc).get("text");

                            if ((QueryExpansion.method == QueryExpansion.PRM1)
                                    || (QueryExpansion.method == QueryExpansion.PRM2)) {
                                expander.addPositionalExpansionDoc(currentQuery, orig_text, orig_ranking[i].score, this.analyzer, reader);
                            } else {
                                expander.addExpansionDoc(orig_text, orig_ranking[i].score, this.analyzer, reader);
                            }
                        } else {
                            logger.warning("Expansion:: Document lookup retrieved no item in document index");
                        }
                    }

                }

            logger.info(currentQuery.examine());
            Map e_terms = expander.expansionTerms(this.reader, currentQuery, query_array.length);
            expander.expandCustomQuery(this.reader,currentQuery, e_terms);
            logger.info(currentQuery.examine());
        }

        
        
    }
    
    
    
    /**
     * 
     * main method with some basic calls for query expansion
     * 
     * @param args
     * @throws ParseException
     * @throws IOException 
     */
    
    public static void main(String[] args) throws ParseException, IOException{
        
        LoggerInitializer.setup();
        
        if (args.length >2){
            
            //set this to true to do the esimation of the DCM background parameter
            //else it will be set to the average document length
            SPUDLMSimilarity.b0est = true;
            
            
            //wt2g 
            // if .b0est is set to true this is over-written
            SPUDLMSimilarity.b0 = 352;
            

                       
            
            
            // Open the lucene index dir in args[0]
            QuerySearch i = new QuerySearch(args[0]);
            
            i.loadTopics(args[1]);
            i.loadQrels(args[2]);

            //set some pseudo relevance parameters
            QueryExpansion.pseudo_rel_docs = 10;
            QueryExpansion.interpolation = 0.5;
            QueryExpansion.num_expansion_terms = 30;

            
            


            int qtype = QuerySearch.title;
            
            
            QueryExpansion.EXPAND = false;
            SPUDLMSimilarity.method = SPUDLMSimilarity.spud;
            SPUDLMSimilarity.omega = 0.8;
            logger.info("SPUD no feedback");
            i.setQuerySet(qtype);
            i.runQuerySet();
            
            
            QueryExpansion.EXPAND = true;
            
            QueryExpansion.method = QueryExpansion.RM3;
            logger.info("SPUD-RM3 optimal");
            i.setQuerySet(qtype);
            i.runQuerySet();
            
            
            QueryExpansion.method = QueryExpansion.SPUDQTM;
            QueryExpansion.spud_omega = 0.8;
            logger.info("SPUD-QTM");
            i.setQuerySet(qtype);
            i.runQuerySet();
            

            
            
        }else{
            logger.info("QueryIndex (index) (topics_file) (qrels_file) \n\n"
                    + "\t\"index\" is the lucene index directory (drop the last forward-slash pointing to the index dir)\n"
                    + "\t\"topics_file\" is the trec topics file\n"
                    + "\t\"qrels\" is the qrels file (binary relevance is assumed)\n"
                    + "\n\n\tNote: The estimate of the background model is calculated each time. "
                    + "\n\t    It could be stored in the index once its calculated to save time.\n"
                    + "\t      Also note that the query effectiveness metrics are written to stdout\n"
                    + "\t      so they can be redirected to a file for analysis.");
        }
    }
    
    

    
}
