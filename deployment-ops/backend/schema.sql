CREATE TABLE IF NOT EXISTS users(username text PRIMARY KEY,salt text NOT NULL,password_hash text NOT NULL,role text NOT NULL CHECK(role IN ('admin','viewer')));
CREATE TABLE IF NOT EXISTS sessions(token text PRIMARY KEY,username text REFERENCES users(username),expires timestamptz NOT NULL);
CREATE TABLE IF NOT EXISTS nodes(id bigserial PRIMARY KEY,name text NOT NULL UNIQUE,url text NOT NULL,token text NOT NULL,last_seen timestamptz);
CREATE TABLE IF NOT EXISTS entries(id bigserial PRIMARY KEY,name text NOT NULL,url text NOT NULL,project text NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS audit(id bigserial PRIMARY KEY,actor text NOT NULL,action text NOT NULL,target text NOT NULL,outcome text NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
