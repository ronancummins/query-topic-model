package logging;


import java.io.IOException;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;




public class LoggerInitializer {
    

  
  static private FileHandler logfile;
  static private Formatter formatter;

  static public void setup() throws IOException {

    //create a new formatter
    formatter = new LogFormatter();
    
    // get the global logger to configure it
    Logger logger = Logger.getLogger("");
    Handler[] handlers = logger.getHandlers();

    if (handlers[0] instanceof ConsoleHandler) {
        //change log format of default console logger
        handlers[0].setFormatter(formatter);
            
        //can remove if neccessary
        //rootLogger.removeHandler(handlers[0]);
    }

    //set the level so only info gets logged
    //logger.setLevel(Level.INFO);

    
    logfile = new FileHandler("output.txt" );
    
    logger.addHandler(logfile);
    logfile.setFormatter(formatter);
        
 
    
  }
}