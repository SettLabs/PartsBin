package settlabs;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import org.tinylog.Logger;
import util.database.SQLiteDB;
import util.database.SqlTable;
import util.tools.TimeTools;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

public class Lcsc {

    public static ArrayList<String[]> processPrices(String sku, String prices){
        // Cleanup
        prices = prices.replace("\n\t","\t");
        prices = prices.replace("\n",";");
        prices = prices.replace("\t","/");
        prices = prices.replaceAll("[\\s+€,]+", ""); // Remove whitespace
        // Split per moq line
        var split = prices.split(";");
        var prep = new ArrayList<String[]>();
        for( var moq : split ){
            var all = moq.split("/");
            prep.add( new String[]{sku,all[0],all[1], TimeTools.formatShortUTCNow()}); // Don't need multiplication
        }
        return prep;
    }
    public static String getSearchPage( String sku ){
        return"https://www.lcsc.com/search?q="+ sku;
    }
    public static String getProductPage( String sku ){
        return "https://www.lcsc.com/product-detail/" + sku + ".html";
    }
    public static String getSkuRegex(){
        return "C\\d{4,}[-]?\\d*";
    }
    private static void fillIn(SQLiteDB db) {
        var link = "https://www.lcsc.com/search?q=";
        //fillInSKU(db, link,"lcsc",getSkuRegex());
    }
    public static void readOrderBom( Path file, SQLiteDB db ){
        Logger.info("Reading LCSC order bom from " + file.toString());

        // Check if db exists, no use without it
        if( db==null ){
            Logger.error("db is null");
            return;
        }
        // Check if the db provided contains a table names purchases
        var tableOpt = db.getTable("purchases");
        if( tableOpt.isEmpty() ){
            Logger.error("No purchases table found");
            return;
        }

        // Decode the filename...
        var name = file.getFileName().toString();
        if( !name.startsWith("LCSC_") ) // Is it a lcsc file?
            return;

        var nameParts = name.split("_");
        if(nameParts.length<4) { // Not enough
            Logger.error("Not a valid filename for LCSC CSV");
            return;
        }
        var orderNr = nameParts[2];
        var orderDate = nameParts[3].substring(0,8);

        // Check if the order number is processed already
        if( db.hasResultSet( "purchases","ordernr = ?", orderNr ) ){
            Logger.info("Order Number already in database, skipping.: "+orderNr );
            return;
        }

        CSVParser parser = new CSVParserBuilder()
                .withSeparator(',')
                .withIgnoreQuotations(false)
                .build();

        try (Reader reader = Files.newBufferedReader(file);
             CSVReader csvReader = new CSVReaderBuilder(reader)
                     .withCSVParser(parser)
                     .build()) {

            processOrderCSV(csvReader,tableOpt.get(),orderNr);
            db.flushAll();
        } catch (IOException | CsvValidationException e) {
            throw new RuntimeException(e);
        }
    }
    private static void processOrderCSV(CSVReader csvReader, SqlTable table, String orderNr ) throws CsvValidationException, IOException {
        var header = csvReader.readNext();
        if( header == null ){
            Logger.error( "Error reading LCSC CSV file, no valid headers. Skipping." );
            return;
        }

        var headerParts = Arrays.stream(header).toList();
        int mpnIndex = headerParts.indexOf("Manufacture Part Number");

        if( mpnIndex==-1 ) { // Check if it contains such column, if not, wrong file
            Logger.error("Error reading LCSC CSV file, no valid manufacture part number.");
            return;
        }
        int quantityIndex = headerParts.indexOf("Quantity");
        if( quantityIndex==-1 ) {
            Logger.error("No Quantity found for LCSC CSV file");
            return;
        }
        int totalIndex = headerParts.indexOf("Ext.Price(€)");
        var currency="€";
        if( totalIndex==-1 ) {
            totalIndex = headerParts.indexOf("Ext.Price($)");
            currency="$";
        }

        if( totalIndex==-1 ) {
            Logger.error("No Ext.Price found for LCSC CSV file");
            return;
        }

        String[] nextRecord;
        while ((nextRecord = csvReader.readNext()) != null) {
            // Process each record
            var mpn = nextRecord[mpnIndex];
            var quantity = nextRecord[quantityIndex];
            var totalPrice=nextRecord[totalIndex];
            var data = new String[]{mpn,orderNr,TimeTools.formatLongNow(),quantity,totalPrice,currency};

            table.doInsert(data);
        }
    }
}
