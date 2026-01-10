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
import java.util.Map;

public class Mouser {

    public static String getSearchPage( String sku ){
        return "https://www.mouser.be/c/?q="+ sku;
    }
    public static String getProductPage( String sku ){
        return getSearchPage(sku);
    }
    public static String getSkuRegex(){
        return "^\\d{2,3}-[A-Za-z0-9\\-./]{2,}$";
    }
    public static ArrayList<String[]> processPrices(String sku, Map<String,String> prices){
        var prep = new ArrayList<String[]>();
        for( var set : prices.entrySet() ){
            var price = set.getValue().replace("\n\t","\t");
            price = price.replace("\n","");
            price = price.replaceAll("[\\s+€,]+", ""); // Remove whitespace
            // Split per moq line
            if( !price.isEmpty())
                prep.add( new String[]{sku, set.getKey(),price, TimeTools.formatShortUTCNow()}); // Don't need multiplication
        }
        return prep;
    }
    public static void readOrderBom(Path file, SQLiteDB db ){
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
        var name = file.getFileName().toString().replace(".csv","");
        if( !name.startsWith("mouser_") ) // Is it a mouser file?
            return;

        var nameParts = name.split("_");
        if(nameParts.length<2) { // Not enough
            Logger.error("Not a valid filename for Mouser CSV");
            return;
        }
        var orderNr = nameParts[1];

        // Check if the order number is processed already
        if( db.hasResultSet( "purchases","ordernr = ?", orderNr ) ){
            Logger.error("Order Number already in database, skipping.: "+orderNr );
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
        int mpnIndex = headerParts.indexOf("Mfr. No:");

        if( mpnIndex==-1 ) { // Check if it contains such column, if not, wrong file
            Logger.error("Error reading Mouser CSV file, no valid manufacture part number.");
            return;
        }
        int quantityIndex = headerParts.indexOf("Order Qty.");
        if( quantityIndex==-1 ) {
            Logger.error("No Quantity found for Mouser CSV file");
            return;
        }
        int totalIndex = headerParts.indexOf("Price (EUR)");
        var currency="€";
        if( totalIndex==-1 ) {
            totalIndex = headerParts.indexOf("Price (DOLLAR)");
            currency="$";
        }

        if( totalIndex==-1 ) {
            Logger.error("No Ext.Price found for Mouser CSV file");
            return;
        }

        String[] nextRecord;
        while ((nextRecord = csvReader.readNext()) != null) {
            // Process each record
            var mpn = nextRecord[mpnIndex];
            var quantity = Integer.parseInt(nextRecord[quantityIndex]);

            var totalPrice=nextRecord[totalIndex].replace(currency,"").trim().replace(",",".");
            var total=String.valueOf(Double.parseDouble(totalPrice)*quantity);

            var data = new String[]{mpn,orderNr,TimeTools.formatLongNow(),String.valueOf(quantity),total,currency};

            table.doInsert(data);
        }
    }
}
