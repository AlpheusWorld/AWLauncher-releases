CREATE TABLE aw_friends_users (id TEXT PRIMARY KEY NOT NULL, token_hash TEXT NOT NULL UNIQUE, name TEXT NOT NULL, last_seen INTEGER NOT NULL);
--> statement-breakpoint
CREATE TABLE aw_friends_presence (user_id TEXT PRIMARY KEY NOT NULL REFERENCES aw_friends_users(id) ON DELETE CASCADE, world TEXT, session_id TEXT);
--> statement-breakpoint
CREATE TABLE aw_friendships (pair TEXT PRIMARY KEY NOT NULL, sender TEXT NOT NULL REFERENCES aw_friends_users(id) ON DELETE CASCADE, recipient TEXT NOT NULL REFERENCES aw_friends_users(id) ON DELETE CASCADE, status TEXT NOT NULL CHECK(status IN ('pending','accepted')));
--> statement-breakpoint
CREATE INDEX aw_friendships_recipient ON aw_friendships(recipient);
--> statement-breakpoint
CREATE INDEX aw_friendships_sender ON aw_friendships(sender);
--> statement-breakpoint
CREATE TABLE aw_friends_invites (host TEXT NOT NULL REFERENCES aw_friends_users(id) ON DELETE CASCADE, guest TEXT NOT NULL REFERENCES aw_friends_users(id) ON DELETE CASCADE, session_id TEXT NOT NULL, expires_at INTEGER NOT NULL, PRIMARY KEY (host,guest));
--> statement-breakpoint
CREATE INDEX aw_friends_invites_expiry ON aw_friends_invites(expires_at);
--> statement-breakpoint
CREATE TABLE aw_friends_rate (key TEXT NOT NULL, bucket INTEGER NOT NULL, hits INTEGER NOT NULL, PRIMARY KEY(key,bucket));
