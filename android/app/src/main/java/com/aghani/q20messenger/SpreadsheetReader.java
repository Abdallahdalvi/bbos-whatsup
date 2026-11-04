package com.aghani.q20messenger;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.xmlpull.v1.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;

/** Read-only, paged cell values. Does not run macros, formulas, scripts or links. */
final class SpreadsheetReader {
    static final int ROWS=80, COLS=20;
    static final class Page {
        final ArrayList<String> sheets=new ArrayList<String>();
        final ArrayList<String[]> rows=new ArrayList<String[]>();
        boolean moreRows, moreColumns; int firstRow,firstColumn;
    }
    static Page read(File file,int sheet,int firstRow,int firstColumn) throws Exception {
        if(file.length()>16*1024*1024)throw new IOException("Spreadsheet is too large for this phone's built-in viewer (16 MB limit). Save the original to view elsewhere.");
        FileInputStream signature=new FileInputStream(file);boolean zipped;try{zipped=signature.read()==0x50 && signature.read()==0x4b;}finally{signature.close();}
        return zipped?readXlsx(file,sheet,firstRow,firstColumn):readXls(file,sheet,firstRow,firstColumn);
    }
    private static Page readXls(File file,int sheetIndex,int firstRow,int firstColumn) throws Exception {
        FileInputStream input=new FileInputStream(file); HSSFWorkbook book=null;
        try {
            book=new HSSFWorkbook(input); Page page=new Page();page.firstRow=firstRow;page.firstColumn=firstColumn;
            for(int i=0;i<book.getNumberOfSheets();i++)page.sheets.add(book.getSheetName(i));
            if(page.sheets.isEmpty())return page;
            Sheet sheet=book.getSheetAt(Math.min(sheetIndex,page.sheets.size()-1));
            DataFormatter format=new DataFormatter(Locale.getDefault());
            page.moreRows=sheet.getLastRowNum()>=firstRow+ROWS;
            int end=Math.min(sheet.getLastRowNum()+1,firstRow+ROWS);
            for(int r=firstRow;r<end;r++) {
                Row row=sheet.getRow(r);String[] values=new String[COLS];Arrays.fill(values,"");
                if(row!=null){page.moreColumns|=row.getLastCellNum()>firstColumn+COLS;
                    for(int c=0;c<COLS;c++) {Cell cell=row.getCell(firstColumn+c);if(cell==null)continue;
                        String value;
                        if(cell.getCellTypeEnum()==CellType.FORMULA) {
                            switch(cell.getCachedFormulaResultTypeEnum()) {
                                case NUMERIC:value=format.formatRawCellContents(cell.getNumericCellValue(),cell.getCellStyle().getDataFormat(),cell.getCellStyle().getDataFormatString());break;
                                case STRING:value=cell.getStringCellValue();break;
                                case BOOLEAN:value=String.valueOf(cell.getBooleanCellValue());break;
                                default:value="";
                            }
                        }else value=format.formatCellValue(cell);
                        values[c]=clip(value);
                    }
                }page.rows.add(values);
            }return page;
        }finally{if(book!=null)book.close();input.close();}
    }
    private static XmlPullParser parser(InputStream input)throws Exception {
        XmlPullParserFactory factory=XmlPullParserFactory.newInstance();factory.setNamespaceAware(true);
        XmlPullParser parser=factory.newPullParser();parser.setInput(input,"UTF-8");return parser;
    }
    private static InputStream entry(ZipFile zip,String name)throws Exception {
        ZipEntry entry=zip.getEntry(name);if(entry==null)throw new IOException("Workbook part missing: "+name);
        if(entry.getSize()>24*1024*1024)throw new IOException("Workbook XML exceeds the mobile viewer limit");
        final InputStream input=zip.getInputStream(entry);
        return new FilterInputStream(input){long total;private void count(int n)throws IOException{if(n>0&&((total+=n)>24*1024*1024))throw new IOException("Workbook XML exceeds the mobile viewer limit");}
            @Override public int read()throws IOException{int n=super.read();if(n>=0)count(1);return n;}
            @Override public int read(byte[] bytes,int offset,int length)throws IOException{int n=in.read(bytes,offset,length);count(n);return n;}};
    }
    private static Page readXlsx(File file,int sheetIndex,int firstRow,int firstColumn)throws Exception {
        ZipFile zip=new ZipFile(file);try {
            Page page=new Page();page.firstRow=firstRow;page.firstColumn=firstColumn;
            ArrayList<String> ids=new ArrayList<String>();HashMap<String,String> targets=new HashMap<String,String>();
            InputStream input=entry(zip,"xl/workbook.xml");try{XmlPullParser p=parser(input);for(int e=p.next();e!=XmlPullParser.END_DOCUMENT;e=p.next())if(e==XmlPullParser.START_TAG&&"sheet".equals(p.getName())){page.sheets.add(p.getAttributeValue(null,"name"));String id=p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");ids.add(id);}}finally{input.close();}
            if(ids.isEmpty())return page;
            input=entry(zip,"xl/_rels/workbook.xml.rels");try{XmlPullParser p=parser(input);for(int e=p.next();e!=XmlPullParser.END_DOCUMENT;e=p.next())if(e==XmlPullParser.START_TAG&&"Relationship".equals(p.getName()))targets.put(p.getAttributeValue(null,"Id"),p.getAttributeValue(null,"Target"));}finally{input.close();}
            ArrayList<String> strings=new ArrayList<String>();
            if(zip.getEntry("xl/sharedStrings.xml")!=null){input=entry(zip,"xl/sharedStrings.xml");try{XmlPullParser p=parser(input);StringBuilder text=null;int characters=0;
                for(int e=p.next();e!=XmlPullParser.END_DOCUMENT;e=p.next()){
                    if(e==XmlPullParser.START_TAG&&"si".equals(p.getName()))text=new StringBuilder();
                    else if(e==XmlPullParser.START_TAG&&"t".equals(p.getName())&&text!=null){String part=p.nextText();if(text.length()<4096)text.append(part.substring(0,Math.min(part.length(),4096-text.length())));}
                    else if(e==XmlPullParser.END_TAG&&"si".equals(p.getName())){String value=text==null?"":text.toString();characters+=value.length();if(characters>2000000||strings.size()>=100000)throw new IOException("Shared-string table exceeds mobile memory limit");strings.add(value);text=null;}
                }}finally{input.close();}}
            String target=targets.get(ids.get(Math.min(sheetIndex,ids.size()-1)));if(target==null)throw new IOException("Worksheet relationship is missing");
            target=target.startsWith("/")?target.substring(1):"xl/"+target;if(target.contains("..")||target.contains(":"))throw new IOException("Unsupported worksheet path");
            input=entry(zip,target);try{XmlPullParser p=parser(input);int row=-1,col=-1,nextCol=0;String type="";String[] values=null;StringBuilder value=new StringBuilder();
                for(int e=p.next();e!=XmlPullParser.END_DOCUMENT;e=p.next()){
                    String tag=p.getName();
                    if(e==XmlPullParser.START_TAG&&"row".equals(tag)){String r=p.getAttributeValue(null,"r");row=r==null?row+1:Integer.parseInt(r)-1;if(row>=firstRow+ROWS){page.moreRows=true;break;}values=row>=firstRow?new String[COLS]:null;if(values!=null)Arrays.fill(values,"");nextCol=0;}
                    else if(e==XmlPullParser.START_TAG&&"c".equals(tag)){String address=p.getAttributeValue(null,"r");col=address==null?nextCol:column(address);nextCol=col+1;type=p.getAttributeValue(null,"t");value.setLength(0);if(col>=firstColumn+COLS)page.moreColumns=true;}
                    else if(e==XmlPullParser.START_TAG&&("v".equals(tag)||"t".equals(tag))){String part=p.nextText();if(value.length()<4096)value.append(part.substring(0,Math.min(part.length(),4096-value.length())));}
                    else if(e==XmlPullParser.END_TAG&&"c".equals(tag)&&values!=null&&col>=firstColumn&&col<firstColumn+COLS){String text=value.toString();if("s".equals(type)){int index=Integer.parseInt(text);text=index>=0&&index<strings.size()?strings.get(index):"";}else if("b".equals(type))text="1".equals(text)?"TRUE":"FALSE";values[col-firstColumn]=clip(text);}
                    else if(e==XmlPullParser.END_TAG&&"row".equals(tag)&&values!=null){while(page.rows.size()<row-firstRow&&page.rows.size()<ROWS)page.rows.add(new String[COLS]);page.rows.add(values);}
                }
            }finally{input.close();}return page;
        }finally{zip.close();}
    }
    private static int column(String address){int col=0;for(int i=0;i<address.length();i++){char c=address.charAt(i);if(c<'A'||c>'Z')break;col=col*26+c-'A'+1;}return Math.max(0,col-1);}
    static String columnName(int value){StringBuilder result=new StringBuilder();for(int n=value+1;n>0;n=(n-1)/26)result.insert(0,(char)('A'+(n-1)%26));return result.toString();}
    private static String clip(String value){return value==null?"":value.length()>4096?value.substring(0,4096)+"…":value;}
    static String html(Page page) {
        StringBuilder out=new StringBuilder("<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{margin:0;background:#10181d;color:#e9edef;font:14px sans-serif}table{border-collapse:collapse}th{background:#26343d}td,th{border:1px solid #38464f;padding:6px;min-width:75px;max-width:260px;word-wrap:break-word;white-space:pre-wrap}</style><table><tr><th>#</th>");
        for(int c=0;c<COLS;c++)out.append("<th>").append(columnName(page.firstColumn+c)).append("</th>");out.append("</tr>");
        for(int r=0;r<page.rows.size();r++){out.append("<tr><th>").append(page.firstRow+r+1).append("</th>");for(String value:page.rows.get(r))out.append("<td>").append(escape(value)).append("</td>");out.append("</tr>");}return out.append("</table>").toString();
    }
    private static String escape(String text){return text==null?"":text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
