create table modids
(
    modId varchar(63) not null primary key,
    id    serial unique
);

create table refs
(
    modId  int      not null references modids (id),
    amount int      not null,
    owner  text     not null,
    member text,
    type   smallint not null,
    constraint references_pk primary key (modId, owner, member, type)
);

create table inheritance
(
    modId      int     not null references modids (id),
    class      text    not null,
    super      text    not null,
    interfaces text [] not null,
    methods    text [] not null,
    constraint inheritance_pk primary key (modId, class)
);