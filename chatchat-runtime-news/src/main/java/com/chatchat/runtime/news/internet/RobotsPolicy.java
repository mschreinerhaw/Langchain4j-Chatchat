package com.chatchat.runtime.news.internet;

import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RobotsPolicy {
    private final PublicWebHttp http;
    private final Map<String,Cached> cache=new ConcurrentHashMap<>();
    private record Cached(String text,long expiresAt) { }
    public RobotsPolicy(PublicWebHttp http){this.http=http;}
    public void require(URI uri,long deadline){
        String origin=uri.getScheme()+"://"+uri.getRawAuthority();var saved=cache.get(origin);
        String text;
        if(saved!=null&&saved.expiresAt()>System.currentTimeMillis())text=saved.text();
        else {
            var r=http.get(origin+"/robots.txt",deadline);
            if(r.status()==404||r.status()==410)text="";
            else if(r.status()>=200&&r.status()<300)text=new String(r.body(),StandardCharsets.UTF_8);
            else throw new PublicWebHttp.Rejected("ROBOTS_UNAVAILABLE");
            if(text.length()>256000)throw new PublicWebHttp.Rejected("ROBOTS_TOO_LARGE");
            if(cache.size()>1024)cache.clear();cache.put(origin,new Cached(text,System.currentTimeMillis()+300000));
        }
        if(!allowed(text,uri))throw new PublicWebHttp.Rejected("ROBOTS_DISALLOWED");
    }
    static boolean allowed(String text,URI uri){
        record Rule(boolean allow,String path) { }
        record Group(List<String> agents,List<Rule> rules) { }
        var groups=new ArrayList<Group>();var agents=new ArrayList<String>();var rules=new ArrayList<Rule>();boolean directives=false;
        for(String raw:text.split("\\R")){
            String line=raw.split("#",2)[0].trim();int colon=line.indexOf(':');if(colon<0)continue;
            String key=line.substring(0,colon).trim().toLowerCase(Locale.ROOT),value=line.substring(colon+1).trim();
            if(key.equals("user-agent")){
                if(directives){groups.add(new Group(List.copyOf(agents),List.copyOf(rules)));agents.clear();rules.clear();directives=false;}
                agents.add(value.toLowerCase(Locale.ROOT));
            }else if(!agents.isEmpty()){directives=true;if((key.equals("allow")||key.equals("disallow"))&&!value.isEmpty())rules.add(new Rule(key.equals("allow"),value));}
        }
        groups.add(new Group(agents,rules));
        String agent=PublicWebHttp.USER_AGENT.toLowerCase(Locale.ROOT),path=normalize(uri.getRawPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery()));
        int selected=groups.stream().flatMap(g->g.agents().stream()).filter(a->!a.equals("*")&&!a.isBlank()&&agent.startsWith(a)).mapToInt(String::length).max().orElse(0);
        int longest=-1;boolean result=true;
        for(var group:groups){
            boolean applies=group.agents().stream().anyMatch(a->selected==0?a.equals("*"):a.length()==selected&&agent.startsWith(a));
            if(!applies)continue;
            for(var rule:group.rules()){
                String p=normalize(rule.path());boolean end=p.endsWith("$");if(end)p=p.substring(0,p.length()-1);
                int length=p.replace("*","").length();
                if(matches(p,path,end)&&(length>longest||length==longest&&rule.allow())){longest=length;result=rule.allow();}
            }
        }
        return result;
    }
    // Literal segments avoid regex backtracking on attacker-controlled robots files.
    private static boolean matches(String pattern,String path,boolean end){
        String[] parts=pattern.split("\\*",-1);
        if(!path.startsWith(parts[0]))return false;
        if(parts.length==1)return !end||path.length()==pattern.length();
        int offset=parts[0].length();
        for(int i=1;i<parts.length;i++){
            String part=parts[i];
            int position=end&&i==parts.length-1?path.length()-part.length():path.indexOf(part,offset);
            if(position<offset||!path.startsWith(part,position))return false;
            offset=position+part.length();
        }
        return true;
    }
    private static String normalize(String value){var out=new StringBuilder();for(int i=0;i<value.length();i++){
        char c=value.charAt(i);if(c=='%'&&i+2<value.length())try{int n=Integer.parseInt(value.substring(i+1,i+3),16);char decoded=(char)n;
            if(n<128&&(Character.isLetterOrDigit(decoded)||"-._~".indexOf(decoded)>=0))out.append(decoded);else out.append(String.format("%%%02X",n));i+=2;continue;
        }catch(NumberFormatException ignored){}out.append(c);
    }return out.toString();}
}
