-- liquibase formatted sql

-- Normalize legacy menu icon values to icon names registered by the frontend.
-- Liquibase records this changeset in DATABASECHANGELOG and runs it once per database.
-- changeset ygj:menu-icon-registry
-- comment Normalize legacy menu icon values to the current frontend icon registry
UPDATE sys_menu
SET icon = CASE icon
  WHEN 'settings' THEN 'setting'
  WHEN 'user-management' THEN 'team'
  WHEN 'mind-mapping' THEN 'apartment'
  WHEN 'bookmark' THEN 'book'
  WHEN 'config' THEN 'setting'
  WHEN 'apps' THEN 'appstore'
  WHEN 'safe' THEN 'safety'
  WHEN 'email' THEN 'mail'
  WHEN 'storage' THEN 'database'
  WHEN 'computer' THEN 'desktop'
  WHEN 'expand' THEN 'fullscreen'
  WHEN 'common' THEN 'appstore'
  WHEN 'select-all' THEN 'check-square'
  WHEN 'find-replace' THEN 'search'
  WHEN 'code-release-managment' THEN 'code'
  ELSE icon
END
WHERE deleted = 0
  AND icon IN (
    'settings',
    'user-management',
    'mind-mapping',
    'bookmark',
    'config',
    'apps',
    'safe',
    'email',
    'storage',
    'computer',
    'expand',
    'common',
    'select-all',
    'find-replace',
    'code-release-managment'
  );
