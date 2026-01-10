package settlabs;

import org.tinylog.Logger;
import util.PartNumberWorkflow;
import util.database.SQLiteDB;
import util.math.MathUtils;
import util.tools.FileTools;
import util.tools.TimeTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ReStock {

    public static void checkGlobal( SQLiteDB db ){
        fillInSKU(db, Lcsc.getSearchPage(""),"lcsc",Lcsc.getSkuRegex());
        fillInSKU(db, Mouser.getSearchPage(""),"mouser",Mouser.getSkuRegex());
    }

    public static void fillInSKU(SQLiteDB db, String link, String supplier, String regex) {
        System.out.println("Looking for SKU's for "+supplier);

        db.connect(false);
        db.addRegexFunction();  // Enable option to use regexp

        if (!db.isValid(1000)) { // Verify connection
            System.err.println("No valid database connection");
            return;
        }
        // Do Query and verify there's a result
        var selectSQL = "SELECT id, manufacturer_name, "+supplier+" FROM global WHERE "+supplier+" != 'None' AND REGEXP('"+regex+"', "+supplier+") = 0";
        var res = db.doSelect(selectSQL);
        if( res.isEmpty() ) {
            System.err.println("Query failed");
            return;
        }
        var data = res.get();
        if( data.isEmpty() ) {
            System.out.println("No query results");
            return;
        }

        // Start processing
        PartNumberWorkflow workflow = new PartNumberWorkflow();
        var list = new ArrayList<String>();

        for( int a =0;a<data.size();a++ ){
            var l = data.get(a);
            System.out.println("\n--- Processing Part " + (a+1) + " of " + data.size() + " ---");
            var mpn = String.valueOf(l.get(1));

            // Execute the copy-wait-read cycle
            var newClipboardContent = workflow.executeCycle(link + mpn);
            if (newClipboardContent == null  ){
                System.out.println("Cycle failed or clipboard content was not text, trying again.");
                a--;
                continue;
            }

            System.out.println("Final Result (New Content): " + newClipboardContent);
            if( newClipboardContent.matches(regex) ){ // Needs to match regex
                System.out.println("Stored...");
                list.add("UPDATE global SET "+supplier+" = '" + newClipboardContent.trim() + "' WHERE manufacturer_name ='" + mpn + "';");
            }else if( newClipboardContent.equals("stop") ){ // Stop command chosen
                System.out.println("Stopping...");
                break;
            }else if( newClipboardContent.length()<=3 ){ // Skip command chosen
                System.out.println("Skipping...");
                list.add("UPDATE global SET "+supplier+" = 'None' WHERE manufacturer_name ='" + mpn + "';");
            }else{
                System.out.println("Failed Regex: "+newClipboardContent );
            }
            // Intermediate dump
            if( list.size()>10 ){
                db.doBatchRun(list);
                list.clear();
            }
        }
        db.doBatchRun(list);
        System.out.println("\nAll part numbers processed.");
    }

    public static void processBom( String bomFileName ){
        var lines = new ArrayList<String>();
        FileTools.readTxtFile(lines,Path.of(bomFileName));

        if( lines.isEmpty())
            return;

        //Convert to array?
        var colSplit = lines.removeFirst().replace("\",",";").replace("\"","").split(";");
        var colTitles = new ArrayList<>(List.of(colSplit));

        int mpnIndex = colTitles.indexOf("Manufacturer name");
        int mouserIndex = colTitles.indexOf("mouser");
        int lcscIndex = colTitles.indexOf("LCSC");
        int qIndex = colTitles.indexOf("Quantity");

        if( mpnIndex==-1 ){
            System.err.println("No manufacturer name column found!");
            return;
        }
        if( qIndex==-1 ){
            System.err.println("No quantity column found!");
            return;
        }
        var name = bomFileName.substring(0,bomFileName.indexOf("."));
        var db = SQLiteDB.createDB("comps", Path.of("components.db"));
        db.connect(false);

        boolean found=false;
        int a=0;
        var suffix="";
        while(!found){
            var exists = db.doSelect("SELECT * FROM bom where boardname='"+name+suffix+"'");
            if( exists.isPresent() && !exists.get().isEmpty()) {
                a++;
                suffix = "_" + a;
            }else{
                found=true;
            }
        }
        name += suffix;
        // Process bom
        for( var bomLine : lines ){
            var cols = bomLine.replace("\",",";").replace("\"","").split(";");
            var mpn = cols[mpnIndex];
            if( mpn.isEmpty() )
                continue;

            var mouser = mouserIndex>=cols.length?"":cols[mouserIndex];
            var lcsc = lcscIndex>=cols.length?"":cols[lcscIndex];
            if( !addToGlobal(db,mpn,mouser,lcsc,"" ) ) // Add component to global one
                System.out.println("Already in global: "+mpn);
            //Insert new data
            var items = new String[]{name, mpn, qIndex >= cols.length ? "0" : cols[qIndex]};
            var insert ="INSERT into bom (boardname,manufacturer_name,quantity) VALUES (?,?,?);";
            if( !db.doPreparedInsert( insert,items ) )
                System.err.println("Insert failed for "+mpn+ " -> "+insert);
        }
        db.finish();
    }

    private static boolean addToGlobal(SQLiteDB lite, String mpn, String mouser, String lcsc, String other ){
        var opt = lite.doSelect( "SELECT * FROM global WHERE manufacturer_name='"+mpn+"';");
        if( opt.isEmpty() )// query failed
            return false;
        var resSet = opt.get();
        if( !resSet.isEmpty() )
            return false;

        PartNumberWorkflow workflow = new PartNumberWorkflow();
        var insert = "INSERT INTO manu_prices (sku,moq,price,timestamp) VALUES (?,?,?,?);";

        // Check if sku's are present.
        if( mouser.isEmpty() ){ // No mouser present
            var sku = getSkuInfo(workflow, Mouser.getSearchPage(mpn), Mouser.getSkuRegex());
            if (!sku.isEmpty())
                mouser = sku;
        }
        if( !mouser.equalsIgnoreCase("None") && !mouser.isEmpty() ) {
            // Gather price info
            doMouserPrice(workflow,lite,insert,mouser);
        }
        if( lcsc.isEmpty() ) { // No lcsc present
            // Get sku
            var sku = getSkuInfo(workflow, Lcsc.getSearchPage(mpn), Lcsc.getSkuRegex());
            if (!sku.isEmpty())
                lcsc = sku;
        }

        if( !lcsc.equalsIgnoreCase("None") && !lcsc.isEmpty() ) {
            var q = "SELECT * FROM lcsc_prices WHERE sku = '"+lcsc+"'";
            var result = lite.doSelect(q,false);
            if( result.isPresent() && result.get().isEmpty()) {
                // Gather price info
                var prices = getSimplePriceInfo(workflow, lcsc, Lcsc.getSearchPage(lcsc));
                if (!prices.isEmpty() && !prices.equalsIgnoreCase("None")) {
                    var res = Lcsc.processPrices(lcsc, prices);
                    res.forEach(row -> lite.doPreparedInsert(insert.replace("manu", "lcsc"), row, true));
                }
            }
        }

        if( resSet.isEmpty() ) {// Not in global yet, so add it
            var insertGlobal = "INSERT INTO global (manufacturer_name,mouser,lcsc, other) VALUES (?,?,?,?);";
            var data = new String[]{mpn,mouser,lcsc,other};
            if( !lite.doPreparedInsert( insertGlobal,data ) )
                System.err.println("Insert failed for "+mpn+ " -> "+insertGlobal);
        }
        lite.commit();
        return true;
    }
    public static void checkPrices( SQLiteDB db ){
        PartNumberWorkflow workflow = new PartNumberWorkflow();
        var insert = "INSERT INTO manu_prices (sku,moq,price,timestamp) VALUES (?,?,?,?);";

        var search = """
                SELECT
                  g.mouser
                FROM
                  global g
                WHERE
                    g.mouser NOT IN (SELECT sku FROM mouser_prices) AND g.mouser != 'None';
                """;
        var res = db.doSelect(search);
        if( res.isPresent() ){
            for( var line : res.get()) {
                var sku = String.valueOf(line.getFirst());
                var ok = doMouserPrice(workflow, db, insert, sku);
                if( !ok ){
                    db.doInsert("UPDATE global SET mouser = 'None' WHERE mouser ='" + sku + "';");
                    db.commit();
                }
            }
        }

        res = db.doSelect(search.replace("mouser","lcsc"));
        if( res.isPresent() ){
            for( var line : res.get()) {
                var sku = String.valueOf(line.getFirst());
                var ok = doLcscPrice(workflow, db, insert, sku);
                if( !ok ){
                    db.doInsert("UPDATE global SET lcsc = 'None' WHERE lcsc ='" + sku + "';");
                    db.commit();
                }
            }
        }
    }
    private static boolean doMouserPrice( PartNumberWorkflow workflow, SQLiteDB lite, String insert, String sku ){
        var result = lite.doSelect( "SELECT * FROM mouser_prices WHERE sku = '"+sku+"'",false);
        var good=false;
        if( result.isPresent() && result.get().isEmpty()) {
            var map = new LinkedHashMap<String, String>();
            map.put("1", "");
            map.put("10", "");
            map.put("25", "");
            map.put("100", "");
            var done = getSteppedPriceInfo(workflow, map, sku, Mouser.getSearchPage(sku));
            if (done) {
                var res = Mouser.processPrices(sku, map);
                good=true;
                res.forEach(row -> lite.doPreparedInsert(insert.replace("manu", "mouser"), row, true));
            }else{
                var data = new String[]{sku,"-1","-1", TimeTools.formatShortUTCNow()};
                lite.doPreparedInsert(insert.replace("manu", "mouser"), data, true);
            }
        }
        return good;
    }
    private static boolean doLcscPrice( PartNumberWorkflow workflow, SQLiteDB lite, String insert, String sku ){
        var result = lite.doSelect( "SELECT * FROM lcsc_prices WHERE sku = '"+sku+"'",false);
        var good=false;
        if( result.isPresent() && result.get().isEmpty()) {

            var prices = getSimplePriceInfo(workflow, sku, Lcsc.getSearchPage(sku));
            if (prices.isEmpty()) {
                var data = new String[]{sku,"-1","-1", TimeTools.formatShortUTCNow()};
                lite.doPreparedInsert(insert.replace("manu", "mouser"), data, true);
            }else if( !prices.equals("None")){
                var res = Lcsc.processPrices(sku, prices);
                good=true;
                res.forEach(row -> lite.doPreparedInsert(insert.replace("manu", "lcsc"), row, true));
            }
        }
        return good;
    }
    private static String getSimplePriceInfo(PartNumberWorkflow workflow, String what, String link){
        while( true ) {
            System.out.println("What is the price for "+what + "?");
            var ncb = workflow.executeCycle(link);
            if (ncb != null) {
                if (ncb.equals("stop")) { // Stop command chosen
                    return "None";
                } else if (ncb.length() <= 3) { // Skip command chosen
                    System.out.println("Skipped...");
                    return "";
                } else {
                    System.out.println("Stored...");
                    return ncb;
                }
            }else{
                System.out.println("Cycle failed or clipboard content was not text, trying again.");
            }
        }
    }
    private static boolean getSteppedPriceInfo(PartNumberWorkflow workflow, Map<String,String> map, String what, String link){
        var old="";
        for( var set : map.entrySet() ){
            System.out.print("What is the price for "+set.getKey() + " moq "+what );
            var ncb = workflow.executeCycle(link);
            if (ncb == null) {
                System.out.println("Cycle failed or clipboard content was not text, trying again.");
                ncb = workflow.executeCycle(link);
               if( ncb==null)
                   return false;
            }
            if (ncb.equals("stop")) { // Stop command chosen
                return false;
            } else if (ncb.length() <= 2) { // Skip command chosen
                System.out.println("  Skipped: "+set.getKey()+ " -> "+ncb);
            } else {
                ncb = ncb.replaceAll("[\\s+€]+", "");
                ncb = ncb.replace(",",".");
                if( ncb.equals(old)){
                    System.out.println("  Skipped: "+set.getKey()+ " -> "+ncb);
                }else{
                    System.out.println("  Stored: "+set.getKey()+ " -> "+ncb);
                    map.put(set.getKey(),ncb);
                }

            }
        }
        System.out.println("SKU done!");
        return true;
    }
    public static String getSkuInfo( PartNumberWorkflow workflow, String link, String regex){
        while(true) {
            System.out.println("Waiting for sku info...");
            var newClipboardContent = workflow.executeCycle(link);

            if (newClipboardContent == null) {
                System.out.println("Cycle failed or clipboard content was not text, trying again.");
                continue;
            }
            System.out.println("Final Result (New Content): " + newClipboardContent);
            if (newClipboardContent.matches(regex)) { // Needs to match regex
                System.out.println("Stored...");
                return newClipboardContent.trim();
            } else if (newClipboardContent.equals("stop")) { // Stop command chosen
                System.out.println("Stopping...");
                return "";
            } else if (newClipboardContent.length() <= 3) { // Skip command chosen
                System.out.println("Skipping...");
                return "None";
            } else {
                System.out.println("Failed Regex: " + newClipboardContent);
            }
        }
    }

    private static void fillInLcscTable(SQLiteDB db){

        db.connect(false);

        // Get lcsc from global that isn't in lcsc
        var query = """
                SELECT g.lcsc, g.manufacturer_name
                FROM global g
                LEFT JOIN lcsc_prices l ON g.lcsc = l.sku
                WHERE l.sku IS NULL AND g.lcsc != 'None';
                """;
        var res = db.doSelect(query);
        if( res.isEmpty() )
            return;
        var rs = res.get();
        // Now iterate over and add
        PartNumberWorkflow workflow = new PartNumberWorkflow();
        var list = new ArrayList<String[]>();
        var insert = "INSERT INTO lcsc_prices (sku,moq,price,timestamp) VALUES (?,?,?,?);";

        if(rs.isEmpty()) {
            System.out.println("No missing LCSC prices in database.");
            return;
        }
        for( int a=0;a<rs.size();a++ ){
            var row = rs.get(a);
            var lcsc = String.valueOf(row.getFirst());
            var link = "https://www.lcsc.com/product-detail/"+lcsc+".html";
            System.out.println("Prices for "+lcsc+"?");
            var ncb = workflow.executeCycle(link);

            if (ncb == null  ){
                System.out.println("Cycle failed or clipboard content was not text, trying again.");
                a--;
                continue;
            }
            System.out.println("Final Result (New Content): " + ncb);
            if( ncb.equals("stop") ){ // Stop command chosen
                System.out.println("Stopping...");
                break;
            }else if( ncb.length()<=3 ){ // Skip command chosen
                System.out.println("Skipping...");
            }else{
                System.out.println("Stored...");
                list.addAll(Lcsc.processPrices(lcsc,ncb));
            }
            // Intermediate dump
            if( list.size()>2 ){
                while( !list.isEmpty() ) {
                    if( !db.doPreparedInsert(insert, list.removeFirst(), false))
                        return;
                }
                db.commit();
            }
        }
        if( list.size()>10 ){
            while( !list.isEmpty() )
                db.doPreparedInsert(insert,list.removeFirst(),false);
            db.commit();
        }
    }
    public static void gatherCost(String bom, int multiple){
        var db = SQLiteDB.createDB("comps", Path.of("components.db"));
        var res = db.doSelect("SELECT * FROM bom WHERE boardname='"+bom+"'");
        if( res.isEmpty())
            return;
        String filename=bom+"_cost"+multiple+".csv";
        int a=1;
        var file = filename.replace(".c","_"+a+".c");
        while( Files.exists( Path.of(file) ) ){
            a++;
            file=filename.replace(".c","_"+a+".c");
        }
        var target=Path.of(file);

        var rows = res.get();
        var full = new ArrayList<Price>();
        FileTools.appendToTxtFile(target,"MPN;SKU;Need;MOQ;Unit Price;Total;Bought;Overflow"+System.lineSeparator());
        for( var row : rows ){
            var mpn = row.get(1);
            var quantity = Integer.parseInt(String.valueOf(row.get(2)))*multiple;
            var sku = selectSingleRow(db,"SELECT mouser,lcsc,other FROM global WHERE manufacturer_name='"+mpn+"';");
            if( sku.length==0)
                return;

            var price = bestPrice(db,getSelectQuery(quantity),sku,quantity);
            if(price==null) {
                System.out.println("No sku for "+mpn);
                continue;
            }
            full.add(price);
            var buy = Math.max(quantity,price.moq);
            var line = mpn+";"+price.sku+";"+quantity+";"+price.moq+";"+price.getSinglePrice()+";"+price.total+";"+buy+";"+(buy-quantity);
            System.out.println(line);
            FileTools.appendToTxtFile(target,line+System.lineSeparator());
        }

        double total=0;
        for( var p : full)
            total+=p.total;
        total =MathUtils.roundDouble(total/multiple,2);
        System.out.println("Total per board: "+total);
    }

    private static String getSelectQuery(int quantity) {
        var prices = """
                SELECT *
                FROM (
                    SELECT sku,moq,price,(price*MAX({q},moq)) as total FROM {supplier}_prices
                    WHERE sku='{sku}' AND moq >= {q}
                    ORDER BY moq asc -- Example: order by moq if you want the highest moq price
                    LIMIT 2
                )
                UNION ALL
                SELECT *
                FROM (
                    SELECT sku,moq,price,(price*MAX({q},moq)) as total FROM {supplier}_prices
                    WHERE sku='{sku}' AND moq <= {q}
                    ORDER BY moq asc
                    LIMIT 2
                );
                """;
        prices = prices.replace("{q}",String.valueOf(quantity)); // Replace quantity
        return prices;
    }
    private static int getMinMoq( SQLiteDB db, String sku, String supplier) {
        var mm = """
                    SELECT sku,moq FROM {supplier}_prices
                    WHERE sku='{sku}'
                    ORDER BY moq asc
                    LIMIT 1
                """;
        mm=mm.replace("{sku}",sku);
        mm= mm.replace("{supplier}",supplier);
        var lin = selectSingleRow(db,mm);
        if( lin.length==0)
            return 1;
        return Integer.parseInt(lin[1]);
    }
    private static Price bestPrice( SQLiteDB db, String prices, String[] sku, int quantity){
        var results = new ArrayList<Price>();

        results.addAll( bestSupplierPrice(db, prices, "mouser",sku[0]) );
        results.addAll( bestSupplierPrice(db, prices, "lcsc",sku[1]) );
        results.addAll( bestSupplierPrice(db, prices, "other",sku[2]) );
        results.forEach( pr -> pr.setTotal(quantity) );
        results.sort( Comparator.comparingDouble(p->p.total) );

        if( results.size()==1)
            return results.getFirst();

        if( results.isEmpty()){
            System.err.println("No prices found for "+String.join(", ", sku));
            return null;
        }

        var cheapest = results.getFirst();
        // Loop through the rest of the list starting from the second item
        for (int i = 1; i < results.size(); i++) {
            var sec = results.get(i);

            // Stop if the next option is too expensive.
            // Since the list is sorted, no subsequent option will be cheap enough.
            if (sec.total >= 1.0) {
                break;
            }

            // Optimization Check: Is 'sec' better than our current 'cheapest'?
            // 'sec' must be cheap AND offer a higher MOQ.
            if (cheapest.moq < sec.moq && (sec.moq-quantity )<= 250) {
                // If 'sec' is cheap and has a higher MOQ, it becomes the new best option
                cheapest = sec;
            }
            // If 'sec' is cheap but has a lower/equal MOQ, we stick with the current 'cheapest'
            // because it's cheaper (since the list is sorted by price)
        }
        return cheapest;
    }
    private static ArrayList<Price> bestSupplierPrice( SQLiteDB db, String query, String supplier, String sku ){
        var q = query.replace("{sku}",String.valueOf(sku));
        q = q.replace("{supplier}",supplier);
        var mm = getMinMoq(db,sku,supplier);
        var prices= new ArrayList<Price>();
        for( var row : selectMultiRow(db,q) ){
            prices.add( new Price( row[0],row[1],mm,row[2]) );
        }
        return prices;
    }
    public static String[] selectSingleRow( SQLiteDB db , String query ){
        var res = db.doSelect(query);
        if( res.isEmpty() )
            return new String[0];
        if( res.get().isEmpty() )
            return new String[0];
        return res.map(lists -> lists.getFirst().stream().map(String::valueOf).toArray(String[]::new)).orElseGet(() -> new String[0]);
    }
    public static ArrayList<String[]> selectMultiRow( SQLiteDB db , String query ){
        var res = db.doSelect(query);
        if( res.isEmpty())
            return new ArrayList<>();
        var list = new ArrayList<String[]>();
        for( var obj : res.get() ){
            list.add(obj.stream().map(String::valueOf).toArray(String[]::new));
        }
        return list;
    }
    public static void processPurchases(){
        try (Stream<Path> stream = Files.list(Path.of("purchases"))) {
            var db = SQLiteDB.createDB("comps", Path.of("components.db"));
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter( path -> path.toString().endsWith(".csv"))
                    .toList();

            files.forEach( file -> Lcsc.readOrderBom(file,db) );
            files.forEach( file -> Mouser.readOrderBom(file,db) );
        } catch (IOException e) {
            Logger.error("Error reading purchases directory",e);
        }
    }
    public static void main(String[] args) {
        // checkPrices(SQLiteDB.createDB("comps", Path.of("components.db")));
       // String filename="burrowv3.csv";
       // processBom(filename);
       // gatherCost( filename.replace(".csv",""),10);

       // fillInLcscTable(db);
        processPurchases();

    }
    private static class Price{
        String sku;
        int moq;
        int min_moq;
        double singlePrice;
        double total;

        public Price( String sku, String moq, int min_moq, String sp ){
            this.sku=sku;
            this.moq = Integer.parseInt(moq);
            this.min_moq=min_moq;
            this.singlePrice= Double.parseDouble(sp);
        }
        public void setTotal( int q ){
            if( q%min_moq!=0){
                q=(q/min_moq+1)*min_moq;
            }
            int buy = Math.max(q,moq);
            total = MathUtils.roundDouble( singlePrice*buy,3);
        }
        public String getSinglePrice(){
            var d = MathUtils.roundDouble(singlePrice,4);
            return String.format("%.4f", d);
        }

        public String toString(){
            return "SKU: "+sku+ " MOQ:"+moq + " Single:"+getSinglePrice()+" Total:"+total;
        }
    }
}