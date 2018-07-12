package sg.com.od.zibiea;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.tika.Tika;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

public class ZibieaDocAnalysis {
    public static String detectDocTypeUsingDetector(InputStream stream) throws IOException {
        Detector detector = new DefaultDetector();
        Metadata metadata = new Metadata();

        MediaType mediaType = detector.detect(stream, metadata);
        return mediaType.toString();
    }

    public static String detectDocTypeUsingFacade(InputStream stream) throws IOException {
        Tika tika = new Tika();
        String mediaType = tika.detect(stream);
        return mediaType;
    }

    public static String extractContentUsingParser(InputStream stream) throws IOException, TikaException, SAXException {
        Parser parser = new AutoDetectParser();
        ContentHandler handler = new BodyContentHandler();
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        parser.parse(stream, handler, metadata, context);
        return handler.toString();
    }

    public static String extractContentUsingFacade(InputStream stream) throws IOException, TikaException {
        Tika tika = new Tika();
        String content = tika.parseToString(stream);
        return content;
    }

    public static Metadata extractMetadatatUsingParser(InputStream stream) throws IOException, SAXException, TikaException {
        Parser parser = new AutoDetectParser();
        ContentHandler handler = new BodyContentHandler();
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        parser.parse(stream, handler, metadata, context);
        return metadata;
    }

    public static Metadata extractMetadatatUsingFacade(InputStream stream) throws IOException, TikaException {
        Tika tika = new Tika();
        Metadata metadata = new Metadata();

        tika.parse(stream, metadata);
        return metadata;
    }
    
    /**
     * @param args
     */
   /* public static void main(String[] args) {
    	
    	 File file = null;
         
         try {
             
        	 file = new File("C:\\od\\cv\\Yong_Chyan_Lim_Singapore_4 06_yrs.d2.docx");
        	 FileInputStream fis = new FileInputStream(file.getAbsolutePath());
//        	 String wordz = extractContentUsingParser(fis); 
        	 String wordz = extractContentUsingFacade(fis); 
        	 
        	 System.out.println(wordz);
             
         } catch (Exception exep) {
             exep.printStackTrace();
         }
		
	}*/
}
