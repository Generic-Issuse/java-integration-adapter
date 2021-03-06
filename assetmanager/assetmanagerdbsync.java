import com.peregrine.ac.api.*;
import com.peregrine.ac.AmException;
import java.util.*;
import java.util.regex.*;
import java.text.*;
import org.json.JSONObject;

class AMDBOper extends DBOper
{
	private int pos = 0;
	private XML[] rowresult;
	private AMDB db;

	public AMDBOper(AMDB db,String sql,List<String> list) throws Exception
	{
		if (list == null || list.size() == 0)
			init(db,sql);
		else
		{
			Matcher matcher = replacementPattern.matcher(sql);
			StringBuffer sb = new StringBuffer();
			int x = 0;
			while(matcher.find())
			{
				if (x >= list.size()) throw new AdapterException("Too many replacement characters " + list.size() + ": " + sql);
				matcher.appendReplacement(sb,Matcher.quoteReplacement(db.getValue(list.get(x))));
				x++;
			}
			if (x < list.size()) throw new AdapterException("Not enough replacement characters " + list.size() + ": " + sql);
			matcher.appendTail(sb);
			init(db,sb.toString());
		}
	}

	public AMDBOper(AMDB db,String sql) throws Exception
	{
		init(db,sql);
	}

	private void init(AMDB db,String sql) throws Exception
	{
		if (Misc.isLog(8)) Misc.log("AQL: " + sql);

		this.db = db;

		if (sql.startsWith("select") || sql.startsWith("SELECT"))
		{
			String out = AmApi.AmQuery(db.getAMConnection(),sql,0,0,true);
			StringBuilder sb = new StringBuilder(out);
			XML xml = new XML(sb);

			XML[] columnlist = xml.getElement("Schema").getElements("Column");
			columnnames = new String[columnlist.length];
			for(int i = 0;i < columnlist.length;i++)
			{
				int index = new Integer(columnlist[i].getAttribute("Index"));
				columnnames[index] = columnlist[i].getAttribute("Name");
			}

			rowresult = xml.getElement("Result").getElements("Row");
			resultcount = rowresult.length;
			if (Misc.isLog(15)) Misc.log("Number of entries returned: " + resultcount);
			return;
		}

		try
		{
			AmApi.AmStartTransaction(db.getAMConnection());
			AmApi.AmDbExecAql(db.getAMConnection(),sql);
			AmApi.AmCommit(db.getAMConnection());
		}
		catch(AmException ex)
		{
			AmApi.AmRollback(db.getAMConnection());
			String message = ex.getMessage();
			if (message.indexOf("Impossible de changer de type de gestion") != -1) // TODO: Add English translation
			{
				throw new AdapterException(message + ": unique constraint");
			}

			Misc.rethrow(ex);
		}
		catch(Exception ex)
		{
			AmApi.AmRollback(db.getAMConnection());
			Misc.rethrow(ex);
		}
	}

	@Override
	public LinkedHashMap<String,String> next() throws Exception
	{
		if (rowresult == null || pos >= rowresult.length) return null;

		LinkedHashMap<String,String> row = new LinkedHashMap<String,String>();

		for(int i = 0;i < columnnames.length;i++)
		{
			String value = rowresult[pos].getValueByPath("Column[@Index='"+i+"']");
			if (value == null) value = "";
			if (value.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"))
			{
				Date date = db.dateformat.parse(value);
				value = Misc.gmtdateformat.format(value);
			}
			row.put(columnnames[i],value);
		}

		pos++;
		return row;
	}

	public void close() throws Exception
	{
	}
}

class AMDB extends DB
{
	private long amconn = 0;
	private String username;
	private static AMDB instance;
	public SimpleDateFormat dateformat;
	public DecimalFormat currencyformat;

	private AMDB() throws Exception
	{
		System.out.print("Connection to AM... ");
		XML xml = javaadapter.getConfiguration().getElementByPath("/configuration/connection[@type='am']");
		if (xml == null) throw new AdapterException("No connection element with type 'am' specified");

		String instance = xml.getValue("instance");
		username = xml.getValue("username");
		String password = xml.getValueCrypt("password");
		if (password == null) password = "";

		amconn = AmApi.AmGetConnection(instance,username,password,"");
		if (amconn == 0)
			throw new AdapterException(xml,"AM connection parameters are incorrect");
		AmApi.AmAuthenticateUser(amconn,username,password);

		dateformat = new SimpleDateFormat(Misc.DATEFORMAT);
		String timezone = xml.getValue("timezone","UTC");
		if (!timezone.equals("local"))
			dateformat.setTimeZone(TimeZone.getTimeZone(timezone));

		currencyformat = (DecimalFormat)NumberFormat.getCurrencyInstance();

		System.out.println("Done");
	}

	@Override
	protected String getDate(String value) throws Exception
	{
		Date date = Misc.gmtdateformat.parse(value);
		return "#" + dateformat.format(date) + "#";
	}

	@Override
	public String getValue(String value,String name) throws Exception
	{
		if (value == null) return "null";
		final Pattern pat = Pattern.compile("l\\w+id",Pattern.CASE_INSENSITIVE);
		Matcher match = pat.matcher(name);
		if (match.matches())
			return value;
		return getValue(value);
	}

	@Override
	public String getConcat(String conn,String field,String addedfield) throws Exception
	{
		return field + " + " + addedfield;
	}

	@Override
	public String getValue(String value) throws Exception
	{
		if (value == null) return "null";
		if (value.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"))
			return getDate(value);
		if (value.matches("\\d{4}-\\d{2}-\\d{2}"))
			// Do not do timezone conversion on a simple date
			return "#" + value + "#";
		if (value.matches("-\\d+") || value.matches("-\\d+\\.\\d+"))
			return value;
		value = value.replace("'","''");
		value = value.replace("\r","");
		value = value.replace("\n","' + char(13) + char(10) + '");
		return "'" + value + "'";
	}

	public synchronized static AMDB getInstance() throws Exception
        {
		if (instance == null)
		{
			instance = new AMDB();
			javaadapter.setForShutdown(instance);
		}
		return instance;
	}

	@Override
	public int execsqlresult(String conn,String sql,List<String> list) throws Exception
	{
		AMDBOper oper = new AMDBOper(this,sql,list);
		return oper.getResultCount();
	}

	@Override
	public int execsqlresult(String conn,String sql) throws Exception
	{
		return execsqlresult(conn,sql,null);
	}

	@Override
	public ArrayList<LinkedHashMap<String,String>> execsql(String conn,String sql) throws Exception
	{
		return execsql(conn,sql,null);
	}

	@Override
	public ArrayList<LinkedHashMap<String,String>> execsql(String conn,String sql,List<String> list) throws Exception
	{
		AMDBOper oper = null;
		ArrayList<LinkedHashMap<String,String>> result = null;

		oper = new AMDBOper(this,sql,list);
		result = new ArrayList<LinkedHashMap<String,String>>();
		LinkedHashMap<String,String> row;

		while((row = oper.next()) != null)
			result.add(row);

		if (Misc.isLog(result.size() > 0 ? 9 : 10)) Misc.log("AQL result [" + conn + "]: " + result);

		return result;
	}

	public long getAMConnection()
	{
		return amconn;
	}
}

class AssetManagerUpdateSubscriber extends DatabaseUpdateSubscriber
{
	public AssetManagerUpdateSubscriber() throws Exception
	{
		db = AMDB.getInstance();
		setQuoteField("");
	}
}

class AssetManagerRestSubscriber extends UpdateSubscriber
{
	protected void add(XML xmldest,XML xmloper) throws Exception
	{
		oper("post",xmldest,xmloper);
	}

	protected void remove(XML xmldest,XML xmloper) throws Exception
	{
		oper("delete",xmldest,xmloper);
	}

	protected void update(XML xmldest,XML xmloper) throws Exception
	{
		oper("put",xmldest,xmloper);
	}

	protected void start(XML xmldest,XML xmloper) throws Exception {}
	protected void end(XML xmldest,XML xmloper) throws Exception {}

	protected void oper(String httpoper,XML xmldest,XML xmloper) throws Exception
	{
		DB db = DB.getInstance();

		XML xml = javaadapter.getConfiguration().getElementByPath("/configuration/connection[@type='am']");
		if (xml == null) throw new AdapterException("No connection element with type 'am' specified");

		XML pubxml = new XML();
		XML pub = pubxml.add("publisher");
		pub.setAttribute("name",xmloper.getParent().getAttribute("name"));
		pub.setAttribute("username",xml.getValue("username",null));
		pub.setAttribute("password",xml.getValue("password",null));
		pub.setAttribute("type","http");
		pub.setAttribute("content_type","application/json");

		String table = xmldest.getAttribute("table");
		if (table == null) throw new AdapterException(xmldest,"dbsync: destination 'table' attribute required");

		XML[] customs = null;
		String oper = xmloper.getTagName();
		if (oper.equals("add")) customs = xmldest.getElements("customadd");
		else if (oper.equals("remove")) customs = xmldest.getElements("customremove");

		StringBuilder where = new StringBuilder("where");
		String sep = "";
		JSONObject js = new JSONObject();

		String id = null;
		XML idxml = xmloper.getElement("ID");
		if (idxml != null)
		{
			id = idxml.getValue();
			if (id == null) id = idxml.getValue("oldvalue",null);
		}
		if (id == null && !oper.equals("add")) throw new AdapterException(xmloper,"ID value required");
		if (id == null) id = "";

		XML[] fields = xmloper.getElements();
		for(XML field:fields)
		{
			String name = field.getTagName();
			String value = field.getValue();
			if (value == null) value = "";
			String type = field.getAttribute("type");
			if (type != null)
			{
				if (type.equals("info")) continue;
				if (type.equals("infoapi")) continue;
				if (type.equals("key"))
				{
					where.append(" " + sep + db.getFieldEqualsValue(name,value));
					sep = "and ";
					js.put(name,value);
					continue;
				}
			}

			if (oper.equals("remove")) continue;
			if (oper.equals("update"))
			{
				XML old = field.getElement("oldvalue");
				if (old == null) continue;
				String oldvalue = old.getValue();
				if (type != null && type.equals("initial") && oldvalue != null) continue;
			}
			js.put(name,value);
		}

		if (customs != null && customs.length > 0)
		{
			httpoper = "put";
			for(XML custom:customs)
			{
				String namecust = custom.getAttribute("name");
				if (namecust == null) throw new AdapterException(custom,"Attribute 'name' required");
				String valuecust = custom.getAttribute("value");
				if (valuecust == null) valuecust = "";
				js.put(namecust,valuecust);
			}
		}

		pub.setAttribute("method",httpoper);
		//pub.setAttribute("url",xmldest.getAttribute("url") + table + "/" + java.net.URLEncoder.encode(where.toString(),"UTF-8").replace("%","\\%"));
		pub.setAttribute("url",xml.getValue("url") + table + "/" + id);

		// Support for session persistency?
		Publisher publisher = Publisher.getInstance();
		String response = publisher.publish(js.toString(),pubxml);

	}
}

public class assetmanagerdbsync
{
	public static void main(String[] args) throws Exception
	{
		long amconn = AmApi.AmGetConnection("db","Admin","Optimum987","");
		if (amconn == 0)
			throw new AdapterException("AM connection paramaters are incorrect");

		String result = AmApi.AmQuery(amconn,"select Name,ComputerDesc from amComputer where AssetTag like 'PC112%'",0,0,true);
		System.out.println("Query " + result);
		AmApi.AmDbExecAql(amconn,"update amComputer set ComputerDesc = 'test' where AssetTag = 'PC112811'");
	}
}
