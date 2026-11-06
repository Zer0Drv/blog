-- Match the exact historical V2 seed fingerprint; preserve already rotated accounts and IDs.
-- The marker is not a BCrypt hash. The bootstrap, when enabled, can replace it after Flyway completes.
UPDATE `user` AS account
CROSS JOIN (SELECT '!legacy-admin-seed-disabled' AS disabled_marker) AS seed
SET account.password = seed.disabled_marker,
    account.session_version = account.session_version + 1
WHERE SHA2(account.password, 256) = '624e482028959dea41a9e136720af0538da0ba8e1b5aad608246f7ea6fa9c17f';
