-- Applied to the Atlas Supabase project as migrations atlas_link_v1 + atlas_link_v1_fix_beat (kept here for reference).
-- Device Link: an end-to-end encrypted relay between one person's own devices. Tables locked (RLS on, no grants);
-- only the functions below are callable by the app (anon): link_send, link_pull (long poll ≤2.2 s), link_head,
-- link_beat (presence), link_frame_put / link_frame_get (newest screen frame only, long poll ≤2.2 s).
-- Messages older than 10 minutes are removed on each send; at most 400 per owner.

create table if not exists public.link_messages (id bigserial primary key, owner text not null check (length(owner) = 64),
  from_device text not null check (length(from_device) between 1 and 80), to_device text not null check (length(to_device) between 1 and 80),
  payload text not null, at timestamptz not null default now());
create index if not exists link_messages_pull on public.link_messages (owner, id);
create index if not exists link_messages_age on public.link_messages (at);
create table if not exists public.link_frames (owner text not null check (length(owner) = 64), device text not null check (length(device) between 1 and 80),
  seq bigint not null, payload text not null, at timestamptz not null default now(), primary key (owner, device));
create table if not exists public.link_presence (owner text not null check (length(owner) = 64), device text not null check (length(device) between 1 and 80),
  info text not null default '', seen_at timestamptz not null default now(), primary key (owner, device));
alter table public.link_messages enable row level security;
alter table public.link_frames enable row level security;
alter table public.link_presence enable row level security;
revoke all on public.link_messages, public.link_frames, public.link_presence from anon, authenticated, public;
revoke all on sequence public.link_messages_id_seq from anon, authenticated, public;
-- Functions (SECURITY DEFINER, search_path public): link_send, link_pull, link_head, link_beat, link_frame_put,
-- link_frame_get — as live in the project (select pg_get_functiondef(...) where proname like 'link_%').
-- EXECUTE revoked from public and granted to anon, authenticated.
