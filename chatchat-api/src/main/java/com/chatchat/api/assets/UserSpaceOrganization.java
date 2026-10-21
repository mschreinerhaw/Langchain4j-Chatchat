package com.chatchat.api.assets;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Logical user folders only. Moving entries never moves facts or changes task/run bindings. */
@Service
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
public class UserSpaceOrganization {
    public static final Set<String> BUILTINS=Set.of("root","data","scripts","documents","history","artifacts","logs");
    public record Folder(String id,String parentId,String name) { }
    public record Entry(String source,String sourceId) { }
    public record Location(String source,String sourceId,String folderId) { }
    public record Catalog(List<Folder> folders,List<Location> locations) { }
    private final JdbcTemplate jdbc;private final UserAssetService assets;
    public UserSpaceOrganization(JdbcTemplate jdbc,UserAssetService assets){this.jdbc=jdbc;this.assets=assets;}
    public Catalog list(UserAssetService.Owner owner){
        assets.space(owner);
        return new Catalog(jdbc.query("select id,parent_id,name from runtime_user_asset_folder where workspace_id=? order by created_at,id",
            (r,n)->new Folder(r.getString(1),r.getString(2),r.getString(3)),owner.id()),
            jdbc.query("select source,source_id,folder_id from runtime_user_asset_location where workspace_id=?",
            (r,n)->new Location(r.getString(1),r.getString(2),r.getString(3)),owner.id()));
    }
    @Transactional public Folder create(UserAssetService.Owner owner,String parent,String name){
        assets.space(owner);requireFolder(owner,parent);
        if(name==null || name.isBlank() || name.length()>100 || name.chars().anyMatch(Character::isISOControl)
            || name.matches(".*[\\\\/:*?\"<>|].*") || Set.of(".","..").contains(name.trim()))
            throw new IllegalArgumentException("Provide a folder name of 1–100 characters without path separators");
        String clean=name.trim();
        if(!jdbc.queryForList("select id from runtime_user_asset_folder where workspace_id=? and parent_id=? and lower(name)=lower(?)",owner.id(),parent,clean).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"A folder with this name already exists");
        var folder=new Folder(UUID.randomUUID().toString(),parent,clean);
        jdbc.update("insert into runtime_user_asset_folder(id,workspace_id,parent_id,name,created_at) values(?,?,?,?,?)",folder.id(),owner.id(),parent,clean,System.currentTimeMillis());
        return folder;
    }
    @Transactional public void move(UserAssetService.Owner owner,String folder,List<Entry> entries){
        move(owner,folder,entries,Set.of(owner.user()));
    }
    @Transactional public void move(UserAssetService.Owner owner,String folder,List<Entry> entries,Set<String> aliases){
        assets.space(owner);requireFolder(owner,folder);
        if(entries==null || entries.isEmpty() || entries.size()>100 || new HashSet<>(entries).size()!=entries.size())
            throw new IllegalArgumentException("Select 1–100 distinct files");
        entries.forEach(entry->requireEntry(owner,entry,aliases));
        for(var entry:entries){
            jdbc.update("delete from runtime_user_asset_location where workspace_id=? and source=? and source_id=?",owner.id(),entry.source(),entry.sourceId());
            jdbc.update("insert into runtime_user_asset_location(workspace_id,source,source_id,folder_id) values(?,?,?,?)",owner.id(),entry.source(),entry.sourceId(),folder);
        }
    }
    @Transactional public void remove(UserAssetService.Owner owner,String id){
        remove(owner,id,Set.of(owner.user()));
    }
    @Transactional public void remove(UserAssetService.Owner owner,String id,Set<String> aliases){
        if(BUILTINS.contains(id))throw new IllegalArgumentException("System purpose folders cannot be deleted");
        requireFolder(owner,id);
        // Deleted legacy records may leave organization metadata. They do not make an empty folder undeletable.
        var locations=jdbc.query("select source,source_id from runtime_user_asset_location where workspace_id=? and folder_id=?",
            (r,n)->new Entry(r.getString(1),r.getString(2)),owner.id(),id);
        for(var entry:locations){
            try{requireEntry(owner,entry,aliases);throw new ResponseStatusException(HttpStatus.CONFLICT,"Move files out before deleting this folder");}
            catch(ResponseStatusException missing){if(missing.getStatusCode()!=HttpStatus.NOT_FOUND)throw missing;
                jdbc.update("delete from runtime_user_asset_location where workspace_id=? and source=? and source_id=?",owner.id(),entry.source(),entry.sourceId());}
        }
        if(!jdbc.queryForList("select id from runtime_user_asset_folder where workspace_id=? and parent_id=?",owner.id(),id).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Remove child folders first");
        jdbc.update("delete from runtime_user_asset_folder where workspace_id=? and id=?",owner.id(),id);
    }
    private void requireFolder(UserAssetService.Owner owner,String id){
        if(id!=null && BUILTINS.contains(id))return;
        if(id==null || jdbc.queryForList("select id from runtime_user_asset_folder where workspace_id=? and id=?",owner.id(),id).isEmpty())missing();
    }
    private void requireEntry(UserAssetService.Owner owner,Entry entry,Set<String> aliases){
        if(entry==null || entry.sourceId()==null || entry.sourceId().length()>64)throw new IllegalArgumentException("Invalid file reference");
        if("asset".equals(entry.source())){assets.get(owner,entry.sourceId());return;}
        String table=switch(Objects.toString(entry.source(),"")){case "python-data"->"ds_python_data_file";case "python-script"->"ds_python_script";default->throw new IllegalArgumentException("Invalid file source");};
        var trusted=new LinkedHashSet<String>();trusted.add(owner.user());if(aliases!=null)trusted.addAll(aliases);
        var params=new ArrayList<Object>();params.add(owner.tenant());params.add(entry.sourceId());params.addAll(trusted);
        if(jdbc.queryForList("select id from "+table+" where tenant_id=? and id=? and owner_id in ("+String.join(",",Collections.nCopies(trusted.size(),"?"))+")",params.toArray()).isEmpty())missing();
    }
    private static void missing(){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User space file or folder not found");}
}
