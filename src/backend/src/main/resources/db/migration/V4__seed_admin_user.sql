insert into admin_user (username, password_hash, role, status)
select 'admin', 'Admin12345', 'super_admin', 'active'
where not exists (select 1 from admin_user where username = 'admin');
