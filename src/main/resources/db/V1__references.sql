create table refs
(
    modId  varchar(63) not null,
    amount int         not null,
    owner  text        not null,
    member text,
    type   smallint    not null,
    constraint references_pk primary key (modId, owner, member, type)
);