package com.chatchat.api.assets;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** User-managed files only. Runtime evidence remains in storage and in task APIs. */
@Service
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
public class UserSpaceFileBrowser {
    public record Item(String source,String sourceId,String folderId,Object original) { }
    public record Page(List<Item> items,long total,int page,int pageSize,long totalFiles,Map<String,Long> folderCounts) { }
    private final JdbcTemplate jdbc;
    private final UserAssetService assets;
    private final UserSpaceOrganization organization;
    public UserSpaceFileBrowser(JdbcTemplate jdbc,UserAssetService assets,UserSpaceOrganization organization){
        this.jdbc=jdbc;this.assets=assets;this.organization=organization;
    }

    public Page list(UserAssetService.Owner owner,Set<String> aliases,String folder,String query,String sort,int requestedPage,int requestedSize){
        var catalog=organization.list(owner);
        if(!"all".equals(folder) && !UserSpaceOrganization.BUILTINS.contains(folder)
            && catalog.folders().stream().noneMatch(item->item.id().equals(folder)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Folder not found");
        if(query==null || query.length()>2000)throw new IllegalArgumentException("Invalid file search");
        var trusted=new LinkedHashSet<String>();trusted.add(owner.user());trusted.addAll(aliases);
        String slots=String.join(",",Collections.nCopies(trusted.size(),"?"));
        var params=new ArrayList<Object>();params.add(owner.id());
        params.add(owner.tenant());params.addAll(trusted);params.add(owner.tenant());params.addAll(trusted);params.add(owner.id());
        String sql="""
            with files as (
              select 'asset' as source,id,file_name,size_bytes,state,created_at as modified,
                case when lower(file_name) like '%.py' or lower(file_name) like '%.sql' then 'scripts'
                  when lower(file_name) like '%.log' then 'logs'
                  when lower(file_name) like '%.csv' or lower(file_name) like '%.xls' or lower(file_name) like '%.xlsx'
                    or lower(file_name) like '%.json' or lower(file_name) like '%.jsonl' or lower(file_name) like '%.parquet'
                    or lower(file_name) like '%.orc' or lower(file_name) like '%.zip' then 'data' else 'documents' end as purpose
              from runtime_workspace_file where workspace_id=? and state<>'DELETED' and
            """+UserAssetService.userManagedSql("")+"""
              union all select 'python-data',id,file_name,file_size,status,
                cast(extract(epoch from updated_at)*1000 as bigint),'data'
              from ds_python_data_file where tenant_id=? and owner_id in (
            """+slots+") and status<>'DELETED' and (purpose is null or purpose not like 'user-space:%') "+"""
              union all select 'python-script',id,file_name,octet_length(source_code),status,
                cast(extract(epoch from updated_at)*1000 as bigint),'scripts'
              from ds_python_script where tenant_id=? and owner_id in (
            """+slots+") "+"""
            ), organized as (
              select f.*,coalesce(l.folder_id,f.purpose) as folder_id from files f
              left join runtime_user_asset_location l on l.workspace_id=? and l.source=f.source and l.source_id=f.id
            )
            """;
        var counts=new LinkedHashMap<String,Long>();
        jdbc.query(sql+"select folder_id,count(*) as amount from organized group by folder_id",r->{counts.put(r.getString(1),r.getLong(2));},params.toArray());
        long totalFiles=counts.values().stream().mapToLong(Long::longValue).sum();
        String where=" where (?='all' or folder_id=?) and (position(lower(?) in lower(file_name))>0 or position(lower(?) in lower(state))>0)";
        params.add(folder);params.add(folder);params.add(query.trim());params.add(query.trim());
        long total=jdbc.queryForObject(sql+"select count(*) from organized"+where,Long.class,params.toArray());
        int size=Math.max(10,Math.min(requestedSize,100)),page=(int)Math.max(1,Math.min(requestedPage,Math.max(1,(total+size-1)/size)));
        String order=switch(sort){case "name"->"lower(file_name),source,id";case "size"->"size_bytes desc,source,id";default->"modified desc,source,id";};
        params.add(size);params.add((long)(page-1)*size);
        var keys=jdbc.query(sql+"select source,id,folder_id from organized"+where+" order by "+order+" limit ? offset ?",
            (r,n)->new Item(r.getString(1),r.getString(2),r.getString(3),null),params.toArray());
        var items=new ArrayList<Item>();
        for(var key:keys){
            Object original;
            if("asset".equals(key.source()))original=assets.get(owner,key.sourceId());
            else {
                // Keys are selected from the authenticated tenant and trusted aliases above.
                String table="python-script".equals(key.source())?"ds_python_script":"ds_python_data_file";
                var detailParams=new ArrayList<Object>();detailParams.add(key.sourceId());detailParams.add(owner.tenant());detailParams.addAll(trusted);
                original=jdbc.queryForObject("select * from "+table+" where id=? and tenant_id=? and owner_id in ("+slots+")",(r,n)->{
                    var value=new LinkedHashMap<String,Object>();value.put("id",r.getString("id"));value.put("fileName",r.getString("file_name"));
                    value.put("status",r.getString("status"));value.put("updatedAt",r.getTimestamp("updated_at").toInstant().toString());
                    if("python-script".equals(key.source())){
                        value.put("sourceCode",r.getString("source_code"));value.put("assetId",r.getString("asset_id"));
                        value.put("folderId",r.getString("folder_id"));value.put("title",r.getString("title"));value.put("currentVersion",r.getInt("current_version"));
                    }else value.put("fileSize",r.getLong("file_size"));
                    return value;
                },detailParams.toArray());
            }
            items.add(new Item(key.source(),key.sourceId(),key.folderId(),original));
        }
        return new Page(items,total,page,size,totalFiles,counts);
    }
}
