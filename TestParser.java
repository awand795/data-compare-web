import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import java.util.List;

public class TestParser {
    public static void main(String[] args) throws Exception {
        Statement statement = CCJSqlParserUtil.parse("select * from demo.trh_penerimaan_lain");
        TablesNamesFinder finder = new TablesNamesFinder();
        List<String> tableList = finder.getTableList(statement);
        System.out.println("Tables: " + tableList);
    }
}
