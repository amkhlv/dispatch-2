# Dispatch

## Package
```
./gradlew distZip
```

## Prepare database

    create table users ("login" TEXT NOT NULL, "password" TEXT NOT NULL);

    create table seminars (id SERIAL, "owner" TEXT NOT NULL,"start_date" DATE NOT NULL,"start_time" TIME NOT NULL,"repeat_weeks" INTEGER NOT NULL,"description" TEXT NOT NULL,"link" TEXT NOT NULL,"show_to_group" INTEGER NOT NULL,"show_to_all" INTEGER NOT NULL);


## Debuggin errors

instead of just:

    "/" bind GET to mainpage

surround like this:

    "/" bind GET to ServerFilters.CatchAll()(mainpage)
