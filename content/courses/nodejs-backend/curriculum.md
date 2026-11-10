# Node.js ועבודה ב-Backend

10 פרקים, 59 יחידות של כ-28 דקות.
בערך 28 שעות האזנה, בלי לספור חזרות.

> הקובץ הזה נוצר אוטומטית מ-`course.json`. לשינויים, ערוך את ה-JSON והרץ `python3 tools/render_curriculum.py`.

## פרק 1: יסודות ה-Backend

**1. מה Backend עושה ולמה הוא קיים** · טיוטה
- השרת הוא הצד שהמשתמש לא שולט בו, ולכן כל מה שצריך להיות אמין קורה בו
- מ-.NET: ה-React הוא הלקוח וה-ASP.NET הוא השרת, בדיוק כמו בעבודה

**2. המסע של בקשה: DNS, TCP ו-TLS** · טיוטה
- מה קורה מהרגע שהלקוח שולח בקשה ועד שהיא מגיעה לקוד שלך
- דורש: מה Backend עושה ולמה הוא קיים

**3. מבנה בקשה ותשובה ב-HTTP** · טיוטה
- Method, URL, Headers ו-Body בבקשה; Status, Headers ו-Body בתשובה
- מ-.NET: מה שמגיע ל-HttpContext.Request ויוצא מ-HttpContext.Response
- דורש: מה Backend עושה ולמה הוא קיים

**5. שיטות HTTP, Safe ו-Idempotent** · טיוטה
- מתי GET, POST, PUT, PATCH ו-DELETE, ולמה חשוב שפעולה תהיה Idempotent
- דורש: מבנה בקשה ותשובה ב-HTTP

**6. קודי סטטוס** · טיוטה
- המשפחות 2xx, 3xx, 4xx ו-5xx, ומתי להחזיר כל קוד נפוץ
- דורש: מבנה בקשה ותשובה ב-HTTP

**7. Stateless ואיפה שומרים מצב** · טיוטה
- HTTP לא זוכר כלום בין בקשות; מצב נשמר במסד נתונים, ב-Cache או אצל הלקוח
- דורש: מבנה בקשה ותשובה ב-HTTP

## פרק 2: סביבת הריצה של Node.js

**9. מה זה Node.js: V8 ו-libuv** · טיוטה
- סביבת ריצה שמריצה JavaScript מחוץ לדפדפן, בנויה ממנוע V8 ומספריית libuv, עם Thread אחד לקוד שלך
- מ-.NET: התפקיד שה-.NET Runtime ממלא עבור קוד C#
- דורש: מה Backend עושה ולמה הוא קיים

**10. Node.js בעומס: Thread Pool, זיכרון ושגיאות** · טיוטה
- מה תוקע או מפיל שרת Node: חישוב כבד, Thread Pool שמתמלא, דליפת זיכרון, ושגיאה שלא נתפסה
- מ-.NET: ב-ASP.NET שגיאה בבקשה אחת מחזירה 500 רק לה; ב-Node היא יכולה להפיל את כל התהליך
- דורש: מה זה Node.js: V8 ו-libuv

**11. Event Loop ו-I/O שלא חוסם** · טיוטה
- איך לולאה אחת מריצה Callback אחרי Callback, מה await עושה, ולמה יש מרוצים גם עם Thread אחד
- מ-.NET: ב-ASP.NET יש Thread Pool שמטפל בבקשות במקביל; ב-Node יש Thread ראשי אחד
- דורש: מה זה Node.js: V8 ו-libuv

**13. Event Loop בעבודה: מקביליות, חתיכות ומדידה** · טיוטה
- Promise.all עם גבול, פירוק עבודה ארוכה לחתיכות, ו-Event Loop Lag כמדד לחסימה
- מ-.NET: ב-ASP.NET ה-Thread Pool מחפה על Thread תקוע; ב-Node אחד תקוע, כולם תקועים
- דורש: Event Loop ו-I/O שלא חוסם, Node.js בעומס: Thread Pool, זיכרון ושגיאות

**14. Microtasks מול Macrotasks** · טיוטה
- למה Promise שהסתיים רץ לפני setTimeout של אפס, ומה זה אומר על סדר הביצוע
- דורש: Event Loop ו-I/O שלא חוסם

**15. סיבוב של הלולאה: setImmediate ו-process.nextTick** · טיוטה
- השלבים של סיבוב אחד בלולאה, מה באמת מפנה את הדרך לבקשות אחרות, ומה קודם לכולם
- דורש: Microtasks מול Macrotasks, Event Loop בעבודה: מקביליות, חתיכות ומדידה

**17. חסימת ה-Event Loop: Worker Threads ו-Cluster** · מתוכנן
- חישוב כבד תוקע את כל השרת; איך מזהים את זה ומה הפתרונות
- דורש: Event Loop ו-I/O שלא חוסם

**18. Callbacks, Promises ו-async/await** · מתוכנן
- שלושה דורות של קוד אסינכרוני, ואיך מטפלים בשגיאות בכל אחד
- מ-.NET: Promise מקביל ל-Task, ו-async/await עובד כמעט אותו דבר כמו ב-C#
- דורש: Event Loop ו-I/O שלא חוסם

**19. מודולים, npm ו-package.json** · מתוכנן
- CommonJS מול ES Modules, תלויות, Semver וקובץ ה-Lock
- מ-.NET: npm מקביל ל-NuGet, ו-package.json מקביל לקובץ ה-csproj
- דורש: מה זה Node.js: V8 ו-libuv

## פרק 3: בניית שרת HTTP

**20. http מובנה מול Express, Fastify ו-NestJS** · מתוכנן
- מה כל שכבה נותנת, ומתי לבחור במה
- מ-.NET: NestJS הכי קרוב ל-ASP.NET: Controllers, Modules ו-DI מובנה
- דורש: מה זה Node.js: V8 ו-libuv, מבנה בקשה ותשובה ב-HTTP

**21. Routing** · מתוכנן
- איך שרת מחליט איזה קוד מטפל בכל כתובת ושיטה, כולל פרמטרים בנתיב
- מ-.NET: כמו Attribute Routing או Minimal APIs ב-ASP.NET
- דורש: http מובנה מול Express, Fastify ו-NestJS

**22. Middleware Pipeline** · מתוכנן
- שרשרת שלבים שכל בקשה עוברת, והחשיבות של הסדר שלהם
- מ-.NET: אותו רעיון בדיוק כמו app.Use ב-ASP.NET
- דורש: Routing

**23. ולידציה ו-DTOs** · מתוכנן
- בדיקת כל קלט בגבול המערכת, והפרדה בין מה שנכנס לבין המודל הפנימי
- מ-.NET: מקביל ל-Model Validation ו-DTOs ב-ASP.NET
- דורש: Middleware Pipeline

**24. טיפול מרכזי בשגיאות** · מתוכנן
- Error Middleware אחד שמתרגם שגיאות לתשובות עקביות, כולל בקוד אסינכרוני
- מ-.NET: כמו UseExceptionHandler ו-ProblemDetails
- דורש: Middleware Pipeline, Callbacks, Promises ו-async/await

**25. קונפיגורציה ומשתני סביבה** · מתוכנן
- הפרדת קונפיגורציה מהקוד, קובצי env ועקרונות 12-Factor
- מ-.NET: מקביל ל-appsettings ו-IConfiguration
- דורש: http מובנה מול Express, Fastify ו-NestJS

## פרק 4: תכנון API

**26. עקרונות REST ומשאבים** · מתוכנן
- חשיבה במשאבים ולא בפעולות, כתובות נכונות ושימוש נכון ב-Methods ובקודים
- דורש: שיטות HTTP, Safe ו-Idempotent, קודי סטטוס

**27. Pagination, סינון ומיון** · מתוכנן
- Offset מול Cursor, ולמה לא מחזירים אף פעם את כל הטבלה
- דורש: עקרונות REST ומשאבים

**28. גרסאות ותאימות לאחור** · מתוכנן
- איך משנים API בלי לשבור לקוחות קיימים
- דורש: עקרונות REST ומשאבים

**29. REST מול GraphQL מול gRPC** · מתוכנן
- מה כל סגנון פותר, ומתי כל אחד מתאים
- דורש: עקרונות REST ומשאבים

**30. תיעוד API עם OpenAPI** · מתוכנן
- חוזה כתוב שממנו מפיקים תיעוד, בדיקות וקוד לקוח
- מ-.NET: מה ש-Swagger מייצר לך אוטומטית ב-ASP.NET
- דורש: עקרונות REST ומשאבים

## פרק 5: נתונים ומסדי נתונים

**31. SQL מול NoSQL** · מתוכנן
- מתי מסד יחסי ומתי מסד מסמכים, ומה המחיר של כל בחירה
- דורש: מה Backend עושה ולמה הוא קיים

**32. מידול נתונים וקשרים** · מתוכנן
- ישויות, קשרים, נרמול, ומתי שווה לשכפל נתונים
- דורש: SQL מול NoSQL

**33. אינדקסים וביצועי שאילתות** · מתוכנן
- איך אינדקס עובד, מה הוא עולה בכתיבה, ואיך יודעים שחסר אחד
- דורש: מידול נתונים וקשרים

**34. טרנזקציות ו-ACID** · מתוכנן
- כמה פעולות שמצליחות יחד או נכשלות יחד, ובעיות של גישה מקבילה
- דורש: מידול נתונים וקשרים

**35. ORM, Query Builders ובעיית N+1** · מתוכנן
- Prisma, TypeORM ו-Knex, מה הם חוסכים ואיפה הם מסתירים בעיות
- מ-.NET: Prisma ו-TypeORM ממלאים את התפקיד של Entity Framework
- דורש: מידול נתונים וקשרים, Callbacks, Promises ו-async/await

**36. Connection Pooling** · מתוכנן
- למה לא פותחים חיבור חדש למסד הנתונים בכל בקשה
- דורש: ORM, Query Builders ובעיית N+1

**37. Migrations** · מתוכנן
- ניהול שינויים במבנה מסד הנתונים כמו שמנהלים קוד
- מ-.NET: כמו EF Core Migrations
- דורש: ORM, Query Builders ובעיית N+1

## פרק 6: ארכיטקטורה של אפליקציית שרת

**38. Controller, Service, Repository** · מתוכנן
- הפרדת אחריות בין קבלת הבקשה, הלוגיקה העסקית והגישה לנתונים
- מ-.NET: אותן שכבות שאתה מכיר מהפרויקט בעבודה
- דורש: Middleware Pipeline, ORM, Query Builders ובעיית N+1

**39. Dependency Injection ב-Node** · מתוכנן
- למה מזריקים תלויות, ואיך עושים את זה בלי ה-Container המובנה של .NET
- מ-.NET: ב-ASP.NET ה-DI מובנה; ב-Express עושים אותו ידנית, וב-NestJS הוא מובנה
- דורש: Controller, Service, Repository

**40. TypeScript בשרת וטיפוסים משותפים** · מתוכנן
- בטיחות טיפוסים בשרת, ושיתוף טיפוסים בין השרת ל-React
- דורש: http מובנה מול Express, Fastify ו-NestJS

**41. מונוליט, מונוליט מודולרי ומיקרו-שירותים** · מתוכנן
- מה מרוויחים ומה משלמים כשמפצלים מערכת, ולמה כמעט תמיד מתחילים ממונוליט
- דורש: Controller, Service, Repository

## פרק 7: זהות ואבטחה

**42. Authentication מול Authorization** · מתוכנן
- מי אתה, מול מה מותר לך, ואיפה כל אחד נבדק
- דורש: Stateless ואיפה שומרים מצב, Middleware Pipeline

**43. Sessions ו-Cookies מול JWT** · מתוכנן
- שתי דרכים לזכור משתמש מחובר, והפשרות של כל אחת
- דורש: Authentication מול Authorization

**44. שמירת סיסמאות: Hashing ו-Salt** · מתוכנן
- למה לא מצפינים סיסמאות אלא עושים להן Hash איטי עם Salt
- דורש: Authentication מול Authorization

**45. OAuth 2.0 ו-OpenID Connect** · מתוכנן
- התחברות עם Google ודומיו, ומה ההבדל בין הרשאה לזיהוי
- דורש: Sessions ו-Cookies מול JWT

**46. Injection, XSS, CSRF ו-CORS** · מתוכנן
- ההתקפות הנפוצות על שרתים, ומה בדיוק CORS מגן ולא מגן
- דורש: Authentication מול Authorization, ולידציה ו-DTOs

**47. Rate Limiting וניהול סודות** · מתוכנן
- הגבלת קצב בקשות, ואיפה מחזיקים מפתחות וסיסמאות
- דורש: Injection, XSS, CSRF ו-CORS, קונפיגורציה ומשתני סביבה

## פרק 8: ביצועים וסקייל

**48. Caching ו-Redis** · מתוכנן
- איפה שומרים תוצאות מוכנות, ולמה ביטול Cache הוא החלק הקשה
- דורש: Stateless ואיפה שומרים מצב, אינדקסים וביצועי שאילתות

**49. Scaling אופקי ו-Load Balancer** · מתוכנן
- הרבה עותקים של אותו שרת, ולמה זה עובד רק אם השרת Stateless
- דורש: Stateless ואיפה שומרים מצב, חסימת ה-Event Loop: Worker Threads ו-Cluster

**50. עבודות רקע ותורי הודעות** · מתוכנן
- להוציא עבודה איטית מהבקשה: BullMQ, RabbitMQ והעיקרון שמאחוריהם
- דורש: Scaling אופקי ו-Load Balancer

**51. Streams ו-Backpressure** · מתוכנן
- עיבוד נתונים גדולים בחתיכות בלי למלא את הזיכרון
- דורש: Event Loop ו-I/O שלא חוסם

## פרק 9: תקשורת בזמן אמת

**52. Polling, Long Polling, SSE ו-WebSockets** · מתוכנן
- ארבע דרכים לעדכן לקוח בזמן אמת, ומתי כל אחת מתאימה
- דורש: מבנה בקשה ותשובה ב-HTTP, Event Loop ו-I/O שלא חוסם

**53. פרוטוקול WebSocket ומחזור החיים של חיבור** · מתוכנן
- Handshake, הודעות בשני הכיוונים, Ping ו-Pong וסגירת חיבור
- מ-.NET: הרעיון ש-SignalR עוטף בעולם של .NET
- דורש: Polling, Long Polling, SSE ו-WebSockets

**54. ws מול Socket.IO, חדרים ושידור** · מתוכנן
- ספרייה רזה מול ספרייה עשירה, ואיך שולחים הודעה לקבוצה
- מ-.NET: Socket.IO מזכיר את SignalR, כולל Groups
- דורש: פרוטוקול WebSocket ומחזור החיים של חיבור

**55. סקייל ל-WebSockets: Sticky Sessions ו-Pub/Sub** · מתוכנן
- מה קורה כשהמשתמשים מחוברים לשרתים שונים, ואיך Redis מחבר ביניהם
- דורש: ws מול Socket.IO, חדרים ושידור, Scaling אופקי ו-Load Balancer

## פרק 10: Production

**56. לוגים, מטריקות ו-Tracing** · מתוכנן
- איך יודעים מה קורה בשרת ב-Production, ולמה לוגים מובנים
- דורש: Middleware Pipeline

**57. אסטרטגיית בדיקות לשרת** · מתוכנן
- Unit, Integration ו-End to End, ומה שווה לבדוק בכל רמה
- דורש: Controller, Service, Repository

**58. Docker, Graceful Shutdown ופריסה** · מתוכנן
- אריזת השרת, כיבוי בלי לאבד בקשות, וריצה בענן
- דורש: קונפיגורציה ומשתני סביבה, Scaling אופקי ו-Load Balancer

**59. CI/CD** · מתוכנן
- בדיקות ופריסה אוטומטיות בכל שינוי
- דורש: אסטרטגיית בדיקות לשרת, Docker, Graceful Shutdown ופריסה
