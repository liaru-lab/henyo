package link.liaru.henyo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Closed schema for the experimental workflow endpoint. No code or nested operations. */
final class WorkflowJson {
    static ConditionalWorkflow.Plan parse(String json) throws JSONException {
        checkBounded(json);
        JSONTokener cursor=new JSONTokener(json);Object value=cursor.nextValue();
        if(!(value instanceof JSONObject)||cursor.nextClean()!=0)throw invalid();
        JSONObject plan=(JSONObject)value;keys(plan,"target","steps","verify","budgetMs");
        JSONObject target=object(plan,"target");keys(target,"package","windowId","displayId");
        ConditionalWorkflow.Target ref=new ConditionalWorkflow.Target(text(target,"package"),integer(target,"windowId",0,Integer.MAX_VALUE),integer(target,"displayId",0,Integer.MAX_VALUE));
        int budget=plan.has("budgetMs")?integer(plan,"budgetMs",1,ConditionalWorkflow.MAX_BUDGET_MS):ConditionalWorkflow.MAX_BUDGET_MS;
        return new ConditionalWorkflow.Plan(ref,steps(array(plan,"steps"),0,new int[]{1}),selector(object(plan,"verify")),budget);
    }
    static void checkBounded(String json) throws JSONException {
        if(json==null||json.getBytes(StandardCharsets.UTF_8).length>32768)throw invalid();
        int depth=0;boolean quoted=false,escaped=false;
        for(int i=0;i<json.length();i++){
            char c=json.charAt(i);
            if(quoted){if(c<0x20)throw invalid();if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}
            else if(c=='"')quoted=true;
            // JSONTokener also accepts single quotes and comments. Reject those before its
            // recursive parser so they cannot disguise nesting from this bound check.
            else if(c=='\''||c=='/'||c=='#')throw invalid();
            else if(c=='{'||c=='['){if(++depth>16)throw invalid();}
            else if(c=='}'||c==']'){if(--depth<0)throw invalid();}
        }
        if(quoted||depth!=0)throw invalid();
    }
    private static List<ConditionalWorkflow.Step> steps(JSONArray values,int depth,int[] count)throws JSONException{
        if(depth>ConditionalWorkflow.MAX_DEPTH)throw invalid();
        List<ConditionalWorkflow.Step> result=new ArrayList<>();
        for(int i=0;i<values.length();i++){
            if(++count[0]>ConditionalWorkflow.MAX_STEPS||!(values.get(i)instanceof JSONObject))throw invalid();
            JSONObject step=(JSONObject)values.get(i);
            if(step.has("op")){
                keys(step,"op","params");String op=text(step,"op");JSONObject params=object(step,"params");
                ConditionalWorkflow.Selector selector=null;
                if("ui.click".equals(op)||"ui.setText".equals(op)){
                    if("ui.click".equals(op))keys(params,"text","field","exact");else keys(params,"text","field","exact","value");
                    JSONObject query=new JSONObject();query.put("text",text(params,"text"));
                    if(params.has("field"))query.put("field",text(params,"field"));
                    if(params.has("exact"))query.put("exact",params.get("exact"));
                    selector=selector(query);params.put("field",selector.field);params.put("exact",true);
                    if("ui.setText".equals(op)&&text(params,"value").codePointCount(0,text(params,"value").length())>1024)throw invalid();
                }else if("ui.scroll".equals(op)){
                    keys(params,"direction","duration");String direction=params.has("direction")?text(params,"direction"):"down";
                    if(!"up".equals(direction)&&!"down".equals(direction))throw invalid();
                    params.put("direction",direction);params.put("duration",params.has("duration")?integer(params,"duration",1,1000):250);
                }else throw invalid();
                result.add(ConditionalWorkflow.Step.action(new ConditionalWorkflow.Action(op,params.toString(),selector)));
            }else if(step.has("semantic")){
                keys(step,"semantic");JSONObject condition=object(step,"semantic");keys(condition,"candidate","question");
                result.add(ConditionalWorkflow.Step.semantic(new ConditionalWorkflow.SemanticCondition(
                        selector(object(condition,"candidate")),text(condition,"question"))));
            }else if(step.has("if")){
                keys(step,"if","then","else");
                result.add(ConditionalWorkflow.Step.branch(selector(object(step,"if")),steps(array(step,"then"),depth+1,count),
                        step.has("else")?steps(array(step,"else"),depth+1,count):java.util.Collections.emptyList()));
            }else if(step.has("wait")){
                keys(step,"wait","timeoutMs");int timeout=step.has("timeoutMs")?integer(step,"timeoutMs",1,ConditionalWorkflow.MAX_WAIT_MS):1000;
                result.add(ConditionalWorkflow.Step.waitFor(selector(object(step,"wait")),timeout));
            }else if(step.has("require")){
                keys(step,"require");result.add(ConditionalWorkflow.Step.require(selector(object(step,"require"))));
            }else throw invalid();
        }
        return result;
    }
    private static ConditionalWorkflow.Selector selector(JSONObject value)throws JSONException{
        keys(value,"text","field","exact");
        if(value.has("exact")&&!Boolean.TRUE.equals(value.get("exact")))throw invalid();
        return new ConditionalWorkflow.Selector(text(value,"text"),value.has("field")?text(value,"field"):"text");
    }
    private static JSONObject object(JSONObject parent,String key)throws JSONException{Object value=parent.get(key);if(!(value instanceof JSONObject))throw invalid();return(JSONObject)value;}
    private static JSONArray array(JSONObject parent,String key)throws JSONException{Object value=parent.get(key);if(!(value instanceof JSONArray))throw invalid();return(JSONArray)value;}
    private static String text(JSONObject parent,String key)throws JSONException{Object value=parent.get(key);if(!(value instanceof String))throw invalid();return(String)value;}
    private static int integer(JSONObject parent,String key,int min,int max)throws JSONException{
        Object value=parent.get(key);if(!(value instanceof Integer)&&!(value instanceof Long))throw invalid();
        long number=((Number)value).longValue();if(number<min||number>max)throw invalid();return(int)number;
    }
    private static void keys(JSONObject object,String...allowed)throws JSONException{
        Set<String> valid=new HashSet<>(java.util.Arrays.asList(allowed));Iterator<String> keys=object.keys();
        while(keys.hasNext())if(!valid.contains(keys.next()))throw invalid();
    }
    private static JSONException invalid(){return new JSONException("invalid workflow");}
}
