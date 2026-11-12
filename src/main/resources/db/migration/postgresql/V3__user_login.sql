create table user_accounts (
 id uuid primary key, display_name varchar(100) not null,
 created_at timestamptz(6) not null, last_login_at timestamptz(6) not null
);
create table social_accounts (
 id uuid primary key, provider varchar(20) not null, provider_user_id varchar(100) not null,
 user_id uuid not null references user_accounts(id), created_at timestamptz(6) not null,
 constraint uk_social_provider_user unique (provider, provider_user_id)
);
create table auth_sessions (
 id uuid primary key, user_id uuid not null references user_accounts(id),
 access_token_hash varchar(64) not null unique, access_expires_at timestamptz(6) not null,
 refresh_token_hash varchar(64) not null unique, previous_refresh_token_hash varchar(64),
 refresh_expires_at timestamptz(6) not null, revoked boolean not null, created_at timestamptz(6) not null
);
create index ix_auth_sessions_previous_refresh on auth_sessions(previous_refresh_token_hash);
