-- SecureBank — one-time password reset for the 8 canonical accounts.
-- Generated 2026-11-08 12:08:41.
-- Run:  mysql -u root -p < "C:\Users\admin\Downloads\securebank-final-backend-source-v2\securebank\scripts\reset-passwords.sql"
-- Then delete this file.

ALTER USER 'customer_app'@'localhost' IDENTIFIED BY 'acUt+m2#rFBu5tzkqt3yDxDer4pXCpwM';
ALTER USER 'customer_migrator'@'localhost' IDENTIFIED BY 'An%g7D7*tyNQZzmtsA*aCkPJw2L7KCji';
ALTER USER 'account_app'@'localhost' IDENTIFIED BY 'Am74LoC#JwzW7^KCFE8Uj4wk7FHYMyXb';
ALTER USER 'account_migrator'@'localhost' IDENTIFIED BY 'aXovtFz!yGv*u*xNNT%x6S*tu%YQQ*UA';
ALTER USER 'ledger_app'@'127.0.0.1' IDENTIFIED BY 'kU2rFVd82zCvo^n5naRZ6BPv4oQinw4j';
ALTER USER 'ledger_migrator'@'127.0.0.1' IDENTIFIED BY 'ig2itp+PBSdPAPr^NHg*R%8y!-XhEa_k';
ALTER USER 'tx_app'@'%' IDENTIFIED BY '3u4Hee4cxLNTn6_Qsyf!22!KsAdKny=&';
ALTER USER 'tx_migrator'@'%' IDENTIFIED BY 'FQMQmJBqHkBtPzA%xHFf9R6Qwn5z_*t2';
FLUSH PRIVILEGES;
