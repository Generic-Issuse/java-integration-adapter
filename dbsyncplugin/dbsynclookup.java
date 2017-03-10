import java.util.*;
import java.io.FileNotFoundException;

enum SyncLookupResultErrorOperationTypes { ERROR, WARNING, EXCEPTION, REJECT_FIELD, REJECT_RECORD, NEWVALUE, NONE };

class SyncLookupResultErrorOperation
{
	private SyncLookupResultErrorOperationTypes type = SyncLookupResultErrorOperationTypes.NONE;
	private String msg;
	private String name;

	public SyncLookupResultErrorOperation() {}

	public SyncLookupResultErrorOperation(SyncLookupResultErrorOperationTypes type)
	{
		this.type = type;
	}

	public SyncLookupResultErrorOperation(SyncLookupResultErrorOperationTypes type,String msg)
	{
		this.type = type;
		this.msg = msg;
	}

	public void setName(String name)
	{
		this.name = name;
	}

	public SyncLookupResultErrorOperationTypes getType()
	{
		return type;
	}

	public String getMessage()
	{
		return msg;
	}

	public String getName()
	{
		return name;
	}
}

class SyncLookup
{
	class SimpleLookup
	{
		class Preload
		{
			private HashMap<String,String> table;
			private HashMap<String,String> datetable;
			private Set<String> fields;
			private String datefield;
			private XML xml;
			private Reader reader;
			private String resultname;
			private boolean loadingdone;

			Preload(XML xml,String resultname) throws Exception
			{
				this.xml = xml;
				this.resultname = resultname;
				loadingdone = false;

				if (Misc.isLog(15)) Misc.log("Lookup: Doing preload for " + fieldname);
				xml.setAttribute("default_field_name",fieldname);

				datefield = xml.getAttribute("date_field");
				if (datefield != null && !"merge_lookup".equals(xml.getTagName()))
					throw new AdapterException(xml,"Invalid date_field attribute");
				if (datefield == null && "merge_lookup".equals(xml.getTagName()))
					throw new AdapterException(xml,"Attribute date_field mandatory for merge preload");

				reader = null;
				try
				{
					reader = ReaderUtil.getReader(xml);
				}
				catch(FileNotFoundException ex)
				{
					xml.setAttributeDeprecated("on_not_found","on_file_not_found");
					OnOper onnotfound = Field.getOnOper(xml,"on_file_not_found",OnOper.exception,EnumSet.of(OnOper.exception,OnOper.ignore,OnOper.warning,OnOper.error));
					switch(onnotfound)
					{
					case exception:
						Misc.rethrow(ex);
					case ignore:
						return;
					case warning:
						Misc.log("WARNING: Ignoring lookup operation since file not found: " + ex.getMessage());
						return;
					case error:
						Misc.log("ERROR: Ignoring lookup operation since file not found: " + ex.getMessage());
						return;
					}
				}
			}

			void updateCache(HashMap<String,String> values)
			{
				if (fields == null || resultname == null) return;
				String value = values.get(resultname);
				if (value == null || value.isEmpty()) return;

				Set<String> keys = values.keySet();
				for(String field:fields)
					if (!keys.contains(field))
						return;

				String key = Misc.getKeyValue(fields,values).toLowerCase();
				table.put(key,value);
			}

			void doInitialLoading(LinkedHashMap<String,String> values) throws Exception
			{
				LinkedHashMap<String,String> result;
				if (reader != null) while((result = reader.next()) != null)
				{
					String value;

					String datevalue = null;
					if (resultname == null)
						value = result.values().iterator().next();
					else
					{
						value = result.get(resultname);
						if (value == null)
							throw new AdapterException(xml,"Preload query doesn't return " + resultname + " field");
					}

					if (table == null) table = new HashMap<String,String>();
					if (datetable == null) datetable = new HashMap<String,String>();

					if (fields == null)
					{
						fields = new TreeSet<String>(result.keySet());
						if (resultname != null) fields.remove(resultname);
						if (datefield != null)
						{
							if (!result.containsKey(datefield))
								throw new AdapterException(xml,"Preload must return date_field " + datefield);
							datevalue = result.get(datefield);
							fields.remove(datefield);
						}
						if (values != null) fields.retainAll(values.keySet()); // Lookup only on common fields
						if (Misc.isLog(15))
						{
							Misc.log("Lookup fields are: " + result.keySet());
							if (values != null) Misc.log("Available fields are: " + values.keySet());
							Misc.log("Common lookup fields are: " + fields);
						}
						if (fields.isEmpty())
						{
							if (Misc.isLog(15)) Misc.log("WARNING: Preload for field '" + fieldname + "' empty since no fields are in common");
							loadingdone = true;
							return;
						}
					}

					String keyvalue = Misc.getKeyValue(fields,result);
					if (keyvalue == null) continue;

					String keyvaluelower = keyvalue.toLowerCase();
					if (table.get(keyvaluelower) != null)
					{
						String duperror = xml.getAttribute("show_duplicates_error");
						if (duperror == null || duperror.equals("true"))
							Misc.log("ERROR: [" + keyvalue + "] Preload for " + fieldname + " returned more than one entries");
						value = null;
					}

					if (value == null || "".equals(value)) continue;

					String[] keyvalues = keyvaluelower.split("\n"); // This won't work if multiple key fields are containing carriage return
					for(String key:keyvalues)
					{
						table.put(key,value);
						if (Misc.isLog(25)) Misc.log("Lookup: Storing preload for " + fieldname + " key " + key + ": " + value);
					}

					if (datevalue != null) datetable.put(keyvaluelower,datevalue);
				}

				if (table == null)
					Misc.log("WARNING: Preload for field '" + fieldname + "' returned empty result");
				loadingdone = true;
			}

			public String getValue(LinkedHashMap<String,String> values) throws Exception
			{
				// Delay preloading until first use
				if (!loadingdone) doInitialLoading(values);

				if (values == null)
				{
					if (table == null) return null;
					Iterator<String> iter = table.values().iterator();
					return  iter.hasNext() ? iter.next() : null;
				}

				if (fields == null) return null;
				String keyvalue = Misc.getKeyValue(fields,values);
				if (keyvalue == null) return ""; // If key is null, do not reject or throw an error

				if (Misc.isLog(25)) Misc.log("Lookup: Preload key for " + fieldname + " is " + keyvalue);

				String keyvaluelower = keyvalue.toLowerCase();
				String result = null;
				if (table != null)
				{
					result = table.get(keyvaluelower);
					if (result == null)
					{
						// Work for simple key only
						String[] keysplit = keyvaluelower.split("\n");
						if (keysplit.length > 1)
						{
							ArrayList<String> resultlist = new ArrayList<String>();
							ArrayList<String> discardedlist = new ArrayList<String>();
							for(String line:keysplit)
							{
								String lineresult = table.get(line);
								if (lineresult == null)
									discardedlist.add(line);
								else
									resultlist.add(lineresult);
							}

							if (resultlist.size() == 0)
								result = null;
							else
							{
								if (discardedlist.size() > 0)
									Misc.log("WARNING: Discarded entries when looking up multiple values for field " + fieldname + ": " + Misc.implode(discardedlist));
								Collections.sort(resultlist,db.collator);
								result = Misc.implode(resultlist,"\n");
							}
						}
					}
					if (result == null && onlookupusekey)
						result = keyvalue;
					if (result == null) return null;
				}

				if (Misc.isLog(25)) Misc.log("Lookup: Getting preload for " + fieldname + " key " + keyvalue + ": " + result);

				if (datefield == null) return result;

				if (!values.containsKey(datefield))
					throw new AdapterException("Extraction must return a value for date_field " + datefield);
				String datevalue = values.get(datefield);
				if (datevalue == null) return result;
				String mergedatevalue = datetable.get(keyvaluelower);
				if (mergedatevalue == null) return null;

				Date date = Misc.dateformat.parse(datevalue);
				Date mergedate = Misc.dateformat.parse(mergedatevalue);

				if (date.after(mergedate)) return null;
				return result;
			}
		}

		private Preload preloadinfo;
		protected XML xmllookup;
		protected SyncLookupResultErrorOperationTypes erroroperation = SyncLookupResultErrorOperationTypes.NONE;
		private boolean onlookupusekey = false;
		private DB db;
		private String opername;

		public SimpleLookup()
		{
		}

		public SimpleLookup(XML xml) throws Exception
		{
			this(xml,fieldname);
		}

		protected SimpleLookup(XML xml,String resultname) throws Exception
		{
			opername = xml.getAttribute("name");
			xmllookup = xml;
			db = DB.getInstance();

			String attr = xml.getAttributeDeprecated("use_key_when_not_found");
			if ("true".equals(attr)) onlookupusekey = true;

			OnOper onlookuperror = Field.getOnOper(xml,"on_lookup_error",OnOper.ignore,EnumSet.of(OnOper.use_key,OnOper.ignore,OnOper.exception,OnOper.error,OnOper.warning));
			switch(onlookuperror)
			{
			case use_key:
				onlookupusekey = true;
				break;
			case exception:
				erroroperation = SyncLookupResultErrorOperationTypes.EXCEPTION;
				break;
			case error:
				erroroperation = SyncLookupResultErrorOperationTypes.ERROR;
				break;
			case warning:
				erroroperation = SyncLookupResultErrorOperationTypes.WARNING;
				break;
			}

			if (Misc.isSubstituteDefault(xml.getValue()))
			{
				String type = xmllookup.getAttribute("type");
				if (!"db".equals(type)) throw new AdapterException(xmllookup,"Unsupported on demand lookup type " + type);
				return;
			}

			preloadinfo = new Preload(xml,resultname);
		}

		protected final String lookup(LinkedHashMap<String,String> row) throws Exception
		{
			if (preloadinfo != null)
				return preloadinfo.getValue(row);

			if (Misc.isLog(15)) Misc.log("Lookup: Doing lookup for " + fieldname);

			String sql = xmllookup.getValue();
			String instance = xmllookup.getAttribute("instance");

			String str = row == null ? sql : db.substitute(instance,sql,row);
			DBOper oper = db.makesqloper(instance,str);

			LinkedHashMap<String,String> result = oper.next();
			if (result == null) return null;

			return Misc.getFirstValue(result);
		}

		public SyncLookupResultErrorOperation oper(LinkedHashMap<String,String> row,String name) throws Exception
		{
			String previous = row.get(name);
			if (previous != null && !previous.isEmpty()) return new SyncLookupResultErrorOperation();

			String value = lookup(row);
			if (value == null)
				return new SyncLookupResultErrorOperation(erroroperation);

			row.put(name,value);
			return new SyncLookupResultErrorOperation(SyncLookupResultErrorOperationTypes.NEWVALUE);
		}

		public final void updateCache(HashMap<String,String> row)
		{
			if (preloadinfo != null) preloadinfo.updateCache(row);
		}

		public final String getName()
		{
			return opername;
		}

		public final String getNameDebug() throws Exception
		{
			if (opername != null) return opername;
			return xmllookup.getTagName();
		}
	}

	class MergeLookup extends SimpleLookup
	{
		public MergeLookup(XML xml) throws Exception
		{
			super(xml,null);
		}

		@Override
		public SyncLookupResultErrorOperation oper(LinkedHashMap<String,String> row,String name) throws Exception
		{
			String value = lookup(row);
			if (value == null)
				return new SyncLookupResultErrorOperation(erroroperation);

			row.put(name,value);
			return new SyncLookupResultErrorOperation(SyncLookupResultErrorOperationTypes.NEWVALUE);
		}
	}

	class DefaultLookup extends SimpleLookup
	{
		public DefaultLookup(XML xml) throws Exception
		{
			super(xml,null);
		}

		@Override
		public SyncLookupResultErrorOperation oper(LinkedHashMap<String,String> row,String name) throws Exception
		{
			String previous = row.get(name);
			if (previous != null && !previous.isEmpty()) return new SyncLookupResultErrorOperation();

			String value = lookup(null);
			if (value == null)
				return new SyncLookupResultErrorOperation(erroroperation);

			row.put(name,value);
			return new SyncLookupResultErrorOperation(SyncLookupResultErrorOperationTypes.NEWVALUE);
		}
	}

	class ExcludeLookup extends SimpleLookup
	{
		SyncLookupResultErrorOperationTypes onexclude = SyncLookupResultErrorOperationTypes.REJECT_RECORD;

		public ExcludeLookup(XML xml) throws Exception
		{
			super(xml,null);

			OnOper scope = Field.getOnOper(xml,"on_exclude",OnOper.reject_record,EnumSet.of(OnOper.ignore,OnOper.reject_field,OnOper.error,OnOper.warning,OnOper.exception));
			switch(scope)
			{
			case ignore:
				onexclude = SyncLookupResultErrorOperationTypes.NONE;
				break;
			case reject_field:
				onexclude = SyncLookupResultErrorOperationTypes.REJECT_FIELD;
				break;
			case error:
				onexclude = SyncLookupResultErrorOperationTypes.ERROR;
				break;
			case warning:
				onexclude = SyncLookupResultErrorOperationTypes.WARNING;
				break;
			case exception:
				onexclude = SyncLookupResultErrorOperationTypes.EXCEPTION;
				break;
			}
		}

		@Override
		public SyncLookupResultErrorOperation oper(LinkedHashMap<String,String> row,String name) throws Exception
		{
			String value = lookup(row);
			if (value == null || value.isEmpty())
				return new SyncLookupResultErrorOperation();
			return new SyncLookupResultErrorOperation(onexclude);
		}
	}

	class IncludeLookup extends ExcludeLookup
	{
		public IncludeLookup(XML xml) throws Exception
		{
			super(xml);
		}

		@Override
		public SyncLookupResultErrorOperation oper(LinkedHashMap<String,String> row,String name) throws Exception
		{
			String value = lookup(row);
			if (value == null)
				return new SyncLookupResultErrorOperation(onexclude);
			return new SyncLookupResultErrorOperation();
		}
	}

	class ScriptLookup extends SimpleLookup
	{
		SyncLookupResultErrorOperationTypes onexception = SyncLookupResultErrorOperationTypes.WARNING;

		public ScriptLookup(XML xml) throws Exception
		{
			xmllookup = xml;

			OnOper scope = Field.getOnOper(xml,"on_exception",OnOper.warning,EnumSet.of(OnOper.warning,OnOper.ignore,OnOper.reject_field,OnOper.error,OnOper.reject_record,OnOper.exception));
			switch(scope)
			{
			case ignore:
				onexception = SyncLookupResultErrorOperationTypes.NONE;
				break;
			case reject_field:
				onexception = SyncLookupResultErrorOperationTypes.REJECT_FIELD;
				break;
			case error:
				onexception = SyncLookupResultErrorOperationTypes.ERROR;
				break;
			case reject_record:
				onexception = SyncLookupResultErrorOperationTypes.REJECT_RECORD;
				break;
			case exception:
				onexception = SyncLookupResultErrorOperationTypes.EXCEPTION;
				break;
			}
		}

		@Override
		public SyncLookupResultErrorOperation oper(LinkedHashMap<String,String> row,String name) throws Exception
		{
			try {
				String value = Script.execute(xmllookup.getValue(),row);
				if (value != null)
				{
					row.put(name,value);
					return new SyncLookupResultErrorOperation(SyncLookupResultErrorOperationTypes.NEWVALUE);
				}
			} catch (AdapterScriptException ex) {
				return new SyncLookupResultErrorOperation(onexception,"SCRIPT EXCEPTION: " + ex.getMessage());
			}

			return new SyncLookupResultErrorOperation();
		}
	}

	private ArrayList<SimpleLookup> lookups = new ArrayList<SimpleLookup>();
	private String fieldname;
	private String defaultlookupvalue;
	private String defaultvalue;
	private int count;

	public SyncLookup(Field field) throws Exception
	{
		fieldname = field.getName();
		XML xml = field.getXML();
		if (xml == null) return;

		XML[] elements = xml.getElements(null);
		for(XML element:elements)
		{
			String name = element.getTagName();
			if (name.equals("default_lookup"))
				lookups.add(new DefaultLookup(element));
			else if (name.equals("lookup"))
				lookups.add(new SimpleLookup(element));
			else if (name.equals("merge_lookup"))
				lookups.add(new MergeLookup(element));
			else if (name.equals("include_lookup"))
				lookups.add(new IncludeLookup(element));
			else if (name.equals("exclude_lookup"))
				lookups.add(new ExcludeLookup(element));
			else if (name.equals("script"))
				lookups.add(new ScriptLookup(element));
		}

		count = lookups.size();

		if (xml.isAttribute("default"))
		{
			defaultvalue = xml.getAttribute("default");
			if (defaultvalue == null) defaultvalue = "";
			if (Misc.isLog(10)) Misc.log("Field: Default " + fieldname + " value: " + defaultvalue);
			count++;
		}
	}

	public SyncLookupResultErrorOperation check(LinkedHashMap<String,String> row,String name) throws Exception
	{
		SyncLookupResultErrorOperationTypes oper = SyncLookupResultErrorOperationTypes.NONE;
		for(SimpleLookup lookup:lookups)
		{
			SyncLookupResultErrorOperation erroroperation = lookup.oper(row,name);
			if (Misc.isLog(25)) Misc.log("Lookup operation " + lookup.getNameDebug() + " returning " + erroroperation.getType() + " oper " + oper);
			if (oper != SyncLookupResultErrorOperationTypes.NEWVALUE || erroroperation.getType() != SyncLookupResultErrorOperationTypes.NONE)
				oper = erroroperation.getType();
			if (erroroperation.getType() != SyncLookupResultErrorOperationTypes.NONE && erroroperation.getType() != SyncLookupResultErrorOperationTypes.NEWVALUE)
			{
				erroroperation.setName(lookup.getName());
				return erroroperation;
			}
		}

		if (defaultvalue != null)
		{
			String value = row.get(name);
			if  (value == null || value.isEmpty())
			{
				row.put(name,Misc.substitute(defaultvalue,row));
				return new SyncLookupResultErrorOperation(SyncLookupResultErrorOperationTypes.NEWVALUE);
			}
		}

		return new SyncLookupResultErrorOperation(oper);
	}

	public void updateCache(HashMap<String,String> row)
	{
		for(SimpleLookup lookup:lookups)
			lookup.updateCache(row);
	}

	public int getCount()
	{
		return count;
	}
}
