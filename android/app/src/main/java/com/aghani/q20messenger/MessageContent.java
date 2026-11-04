package com.aghani.q20messenger;
import org.json.JSONObject;
/** Protocol records stay in SQLite, separate from user-message presentation. */
final class MessageContent {
    static final String SILENT_SQL="'e2e_notification','protocol','notification'";
    static final String NO_UNREAD_SQL=SILENT_SQL+",'gp2'";
    static boolean silent(String type){return "e2e_notification".equals(type)||"protocol".equals(type)||"notification".equals(type);}
    static boolean placeholder(String type,String body){return body==null||body.trim().length()==0||(type+" received").equalsIgnoreCase(body.trim())||(type+" recieved").equalsIgnoreCase(body.trim());}
    static String body(String type,String body,JSONObject rich){
        if(!placeholder(type,body))return body;
        String caption=rich==null?"":rich.optString("caption","");if(caption.length()>0&&!placeholder(type,caption))return caption;
        if("gp2".equals(type))return "Group updated";
        if("ciphertext".equals(type))return "Waiting for this message from WhatsApp";
        if("notification_template".equals(type))return "Business notification · content unavailable";
        if(silent(type))return "Security update";
        return body==null?"":body;
    }
    static String preview(String body){if(body==null||body.trim().length()==0)return "";for(String type:new String[]{"gp2","e2e_notification","notification_template","protocol","notification","ciphertext"})if(placeholder(type,body))return body(type,body,null);return body;}
}


