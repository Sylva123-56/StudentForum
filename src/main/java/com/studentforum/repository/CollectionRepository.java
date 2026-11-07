package com.studentforum.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionRepository {
    private final JdbcTemplate db;

    public CollectionRepository(JdbcTemplate db) { this.db=db; }

    private String table(String kind) {
        return switch(kind) {
            case "collections", "columns" -> kind;
            default -> throw new IllegalArgumentException("Unsupported collection kind");
        };
    }

    public List<Map<String,Object>> search(String kind, String q, boolean mine, boolean featured,
            boolean favorite, boolean following, long user, int offset) {
        String sql="SELECT c.*,u.username FROM "+table(kind)+" c JOIN users u ON u.id=c.owner_id WHERE "
                +(mine?"c.owner_id=?":"c.status='active' AND ?>=0")
                +" AND (?='' OR LOCATE(?,c.title)>0 OR LOCATE(?,c.description)>0)";
        List<Object> args=new ArrayList<>(List.of(user,q,q,q));
        if(kind.equals("collections")) {
            if(featured) sql+=" AND c.is_featured=TRUE";
            if(favorite) {
                sql+=" AND EXISTS(SELECT 1 FROM collection_favorites f WHERE f.collection_id=c.id AND f.user_id=?)";
                args.add(user);
            }
            if(following) {
                sql+=" AND EXISTS(SELECT 1 FROM collection_follows f WHERE f.collection_id=c.id AND f.user_id=?)";
                args.add(user);
            }
        }
        sql+=" ORDER BY "+(kind.equals("collections")?"c.is_featured DESC,":"")+"c.id DESC LIMIT 21 OFFSET ?";
        args.add(offset);
        return db.queryForList(sql,args.toArray());
    }

    public int update(String kind, String title, String description, String cover, long id) {
        return db.update("UPDATE "+table(kind)+" SET title=?,description=?,cover_url=? WHERE id=?",title,description,cover,id);
    }

    public List<Map<String,Object>> items(long id) {
        return db.queryForList("SELECT * FROM collection_items WHERE collection_id=? ORDER BY sort_order,id",id);
    }

    public Map<String,Object> followBy(long id, long user) {
        return db.queryForList("SELECT 1 FROM collection_follows WHERE collection_id=? AND user_id=?",id,user).stream().findFirst().orElse(null);
    }

    public Map<String,Object> favoriteBy(long id, long user) {
        return db.queryForList("SELECT 1 FROM collection_favorites WHERE collection_id=? AND user_id=?",id,user).stream().findFirst().orElse(null);
    }

    public Map<String,Object> lockCollection(long id) {
        return db.queryForList("SELECT id FROM collections WHERE id=? FOR UPDATE",id).stream().findFirst().orElse(null);
    }

    public int upsertItem(long id, String type, long target, Object title, long sort) {
        return db.update("INSERT INTO collection_items(collection_id,item_type,item_id,title,sort_order) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE sort_order=VALUES(sort_order)",id,type,target,title,sort);
    }

    public int recountItems(long id) {
        return db.update("UPDATE collections SET item_count=(SELECT COUNT(*) FROM collection_items WHERE collection_id=?) WHERE id=?",id,id);
    }

    public Map<String,Object> findItem(long itemId, long id) {
        return db.queryForList("SELECT id FROM collection_items WHERE id=? AND collection_id=?",itemId,id).stream().findFirst().orElse(null);
    }

    public int reorderItem(long sort, long itemId, long id) {
        return db.update("UPDATE collection_items SET sort_order=? WHERE id=? AND collection_id=?",sort,itemId,id);
    }

    public int removeItem(long itemId, long id) {
        return db.update("DELETE FROM collection_items WHERE id=? AND collection_id=?",itemId,id);
    }

    public void toggleInteraction(long id, long user, boolean follow, boolean active) {
        String table=follow?"collection_follows":"collection_favorites";
        if(active) db.update("INSERT IGNORE INTO "+table+"(collection_id,user_id) VALUES(?,?)",id,user);
        else db.update("DELETE FROM "+table+" WHERE collection_id=? AND user_id=?",id,user);
    }

    public int recountFollowers(long id) {
        return db.update("UPDATE collections SET follower_count=(SELECT COUNT(*) FROM collection_follows WHERE collection_id=?) WHERE id=?",id,id);
    }

    public List<Map<String,Object>> episodes(long id, long owner, long user) {
        return db.queryForList("SELECT * FROM column_episodes WHERE column_id=? AND (status='published' OR ?=?) ORDER BY episode_no",id,owner,user);
    }

    public Map<String,Object> lockColumn(long id) {
        return db.queryForList("SELECT id FROM columns WHERE id=? FOR UPDATE",id).stream().findFirst().orElse(null);
    }

    public Map<String,Object> episodeNumber(long id, long no) {
        return db.queryForList("SELECT id FROM column_episodes WHERE column_id=? AND episode_no=?",id,no).stream().findFirst().orElse(null);
    }

    public Map<String,Object> findEpisode(long episodeId, long id) {
        return db.queryForList("SELECT id FROM column_episodes WHERE id=? AND column_id=?",episodeId,id).stream().findFirst().orElse(null);
    }

    public int updateEpisode(String title, String content, String status, long episodeId, long id) {
        return db.update("UPDATE column_episodes SET title=?,content=?,status=? WHERE id=? AND column_id=?",title,content,status,episodeId,id);
    }

    public List<Map<String,Object>> adminCollections(int offset) {
        return db.queryForList("SELECT c.*,u.username FROM collections c JOIN users u ON u.id=c.owner_id ORDER BY c.id DESC LIMIT 21 OFFSET ?",offset);
    }

    public Map<String,Object> findCollectionId(long id) {
        return db.queryForList("SELECT id FROM collections WHERE id=?",id).stream().findFirst().orElse(null);
    }

    public int recommend(boolean featured, String status, long id) {
        return db.update("UPDATE collections SET is_featured=?,status=? WHERE id=?",featured,status,id);
    }

    public long insert(String kind, long user, String title, String description, String cover) {
        var key=new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO "+table(kind)+"(owner_id,title,description,cover_url) VALUES(?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] fields={user,title,description,cover};
            for(int i=0;i<fields.length;i++) statement.setObject(i+1,fields[i]);
            return statement;
        },key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    public long insertEpisode(long id, Long noteId, String title, String content, long no, String status) {
        var key=new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO column_episodes(column_id,note_id,title,content,episode_no,status) VALUES(?,?,?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] fields={id,noteId,title,content,no,status};
            for(int i=0;i<fields.length;i++) statement.setObject(i+1,fields[i]);
            return statement;
        },key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }
}
