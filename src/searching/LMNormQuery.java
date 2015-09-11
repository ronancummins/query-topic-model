
package searching;

import java.io.IOException;
import java.util.logging.Logger;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.queries.CustomScoreProvider;
import org.apache.lucene.queries.CustomScoreQuery;
import org.apache.lucene.search.Query;


/**
 *
 * @author ronanc
 */
public class LMNormQuery extends CustomScoreQuery{

    private final static Logger logger = Logger.getLogger( LMNormQuery.class.getName());
    
    private final int queryLen;
    
    
    public LMNormQuery(Query subQuery) {
        super(subQuery);
       
        //logger.info("HERE: " + subQuery.toString());
        
        queryLen = subQuery.toString().split(" ").length;
        
        
    }
    
    /**
     *
     * @param context
     * @return
     * @throws IOException
     */
    @Override
    protected CustomScoreProvider getCustomScoreProvider(LeafReaderContext context)
            throws IOException{
        return new LMNormScoreProvider(context, queryLen);
    }
    
    
    
    
}
