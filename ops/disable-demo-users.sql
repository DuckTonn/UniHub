\set ON_ERROR_STOP on
UPDATE users
SET status = 'INACTIVE'
WHERE upper(status) = 'ACTIVE'
  AND (
    lower(email) IN ('admin@unihub.edu.vn', 'staff@unihub.edu.vn',
        '21127001@student.unihub.edu.vn', '21127002@student.unihub.edu.vn')
    OR lower(email) ~ '^221270(0[1-9]|10)@student[.]unihub[.]edu[.]vn$'
  );
