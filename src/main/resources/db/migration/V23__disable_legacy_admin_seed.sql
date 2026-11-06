-- Match the exact historical V2 seed fingerprint; preserve already rotated accounts and IDs.
-- The marker is not a BCrypt hash. The bootstrap, when enabled, can replace it after Flyway completes.
UPDATE `user`
SET password = '!legacy-admin-seed-disabled', session_version = session_version + 1
WHERE SHA2(password, 256) = '624e482028959dea41a9e136720af0538da0ba8e1b5aad608246f7ea6fa9c17f';
