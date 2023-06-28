# mockingbird

mockingbird - сервис эмуляции REST-сервисов и сервисов с интерфейсами-очередями

[Руководство по инсталляции](deployment.md)

[Руководство по настройке](configuration.md)

[Работа с очередями](message-brokers.md)

## Общие принципы работы

mockingbird поддерживает следующие сценарии:

* прогон конкретного кейса с конкретным набором событий и HTTP/GRPC ответов
* постоянная имитация happy-path для обеспечения автономности контура(ов)

Типы конфигураций:
* countdown - автономные конфигурации для тестирования конкретного сценария. Имеют наивысший приоритет при разрешении неоднозначностей. Каждый мок срабатывает n раз (количество задаётся при создании). Автоматически удаляются в полночь.
* ephemeral - конфигурации, автоматически удаляемые в полночь. Если одновременно вызывают метод/приходит сообщение, для которого подходит countdown и ephemeral моки - сработает countdown.
* persistent - конфигурация, предназначеная для постоянной работы. Имеет наименьший приоритет

>Пример небольшого кейса (короткая заявка) - в конце спецификации

## Сервисы

Для упорядочения моков в UI и минимизации количества конфликтных ситуаций в mockingbird реализованы т.н. сервисы. Каждый мок (как HTTP так и сценарий) всегда принадлежит к какому-то из сервисом.
Сервисы создаются заранее и хранятся в базе. Сервис имеет suffix (являющийся по совместительству уникальным id сервиса) и человекочитаемый name.

## Шаблонизатор JSON

Для достижения гибкости при сохранении относительной простоты конфигов в сервисе реализован JSON шаблонизатор. Для начала простой пример:

Шаблон:
```javascript
{
  "description": "${description}",
  "topic": "${extras.topic}",
  "comment": "${extras.comments.[0].text}",
  "meta": {
    "field1": "${extras.fields.[0]}"
  }
}
```

Значения для подстановки:
```javascript
{
  "description": "Some description",
  "extras": {
    "fields": ["f1", "f2"],
    "topic": "Main topic",
    "comments": [
      {"text": "First nah!"}, {"text": "Okay"}
    ]
  }
}
```

Результат:
```javascript
{
  "description": "Some description",
  "topic": "Main topic",
  "comment": "First nah!",
  "meta": {
    "field1": "f1"
  }
}
```

В данный момент поддерживается следующий синтаксис:
* `${a.[0].b}` - подстановка значения (JSON)
* `${/a/b/c}` - подстановка значения (XPath)

ВНИМАНИЕ! НЕ ИСПОЛЬЗУЙТЕ НЕЙМСПЕЙСЫ В XPATH ВЫРАЖЕНИЯХ

## Шаблонизатор XML

Шаблон:
```
<root>
    <tag1>${/r/t1}</tag1>
    <tag2 a2="${/r/t2/@a2}">${/r/t2}</tag2>
</root>
```

Значения для подстановки:
```
<r>
    <t1>test</t1>
    <t2 a2="attr2">42</t2>
</r>
```

Результат:
```
<root>
    <tag1>test</tag1>
    <tag2 a2="attr2">42</tag2>
</root>
```

## Состояния (state)

Для поддержки сложных сценариев сервис поддерживает сохранение произвольных состояний. Состояние - документ с произвольной схемой, технически состояние - документ в mongodb. Запись новых состояний может происходить:
* при записи в state (секция persist) с пустым (или отсутствующим) предикатом (секция state)

## Манипуляции со state

State аккумулятивно дописывается. Разрешено переписывание полей.

Поля, по которым будем производиться поиск (используемые в предикатах) должны начинаться с "_".
> для таких полей будет автоматически создаваться sparse индекс

Префиксы:
* `seed` - значения из блока seed (рандомизируемые на старте заявки)
* `state` - текущий state
* `req` - тело запроса (режимы json, jlens, xpath)
* `message` - тело собщения (в сценариях)
* `query` - query параметры (в заглушках)
* `pathParts` - значения, извлекаемые из URL (в заглушках) см. `Экстрация данных из URL`
* `extracted` - извлечённые значения
* `headers` - HTTP заголовки

```javascript
{
  "a": "Просто строка", //В поле "a" записывается константа (может быть любое JSON значение)
  "b": "${req.fieldB}", //В поле "b" записывается значение из поля fieldB запроса
  "c": "${state.c}", //В поле "c" записывается значение из поля "c" текущего состояния
  "d": "${req.fieldA}: ${state.a}" //В поле d запишется строка, содержащая req.fieldA и state.a
}
```

## Поиск state

Предикаты для поиска state перечисляются в блоке `state`. Пустой объект (`{}`) в поле state недопустим.
Для поиска state можно использовать данные запроса (без префикса), query параметры (префикс `__query`), значения, извлекаемые из URL (префикс `__segments`) и HTTP заголовки (префикс `__headers`)

Пример:

```javascript
{
  "_a": "${fieldB}", //поле из тела запроса
  "_b": "${__query.arg1}", //query параметр
  "_c": "${__segments.id}", //сегмент URL, см. `Экстрация данных из URL`
  "_d": "${__headers.Accept}" //HTTP заголовок
}
```


## Seeding

Иногда возникает необходимость сгенерировать случайное значение и сохранить и/или вернуть его в результате работы мока.
Для поддержки таких сценариев сделано поле seed, позволяющее задать переменные, которые будут сгенерированы
при инициализации мока. Это позволяет избежать необходимости пересоздавать моки с захардкожеными id

В seed'ах поддерживается синтаксис псевдофункций:
* `%{randomString(n)}` - подстановка случайной строки длиной n
* `%{randomString("ABCDEF1234567890", m, n)}` - подстановка случайной строки, состоящей из символов `ABCDEF1234567890` длиной в интервале [m, n)
* `%{randomNumericString(n)}` - подстановка случайной строки, состоящей только из цифр, длиной n
* `%{randomInt(n)}` - подстановка случайного Int в диапазоне [0, n)
* `%{randomInt(m,n)}` - подстановка случайного Int в диапазоне [m, n)
* `%{randomLong(n)}` - подстановка случайного Long в диапазоне [0, n)
* `%{randomLong(m,n)}` - подстановка случайного Long в диапазоне [m, n)
* `%{UUID}` - подстановка случайного UUID
* `%{now(yyyy-MM-dd'T'HH:mm:ss)}` - текущее время в заданном [формате](https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html)
* `%{today(yyyy-MM-dd)}` - текущая дата в заданном [формате](https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html)

Можно определять строки со сложным форматом: `%{randomInt(10)}: %{randomLong(10)} | %{randomString(12)}`, поддерживаются все псевдофункции из списка выше

## Резолвинг заглушек/сценариев

> Найденые загулшки - кандидаты, оставшиеся после валидации URL, заголовков и тела запроса
> Найденые сценарии - кандидаты, оставшиеся после валидации тела сообщения

| Найденые заглушки (сценарии) | Требуется состояние | Найдено состояний | Результат |
| ---------------------------  | ------------------- | ----------------- | --------- |
| №1                           | нет                 | -                 | Сработает №1 |
| №1                           | да                  | 0                 | Ошибка |
| №1                           | да                  | 1                 | Сработает №1 |
| №1<br>№2                     | нет<br>нет          | -                 | Ошибка |
| №1<br>№2                     | нет<br>да           | -<br>0            | Сработает №1 |
| №1<br>№2                     | нет<br>да           | -<br>1            | Сработает №2 |
| №1<br>№2                     | нет<br>да           | -<br>2 (и более)  | Ошибка |
| №1<br>№2                     | да<br>да            | 0<br>0            | Ошибка |
| №1<br>№2                     | да<br>да            | 0<br>1            | Сработает №2 |
| №1<br>№2                     | да<br>да            | 0<br>2 (и более)  | Ошибка |
| №1<br>№2                     | да<br>да            | 1<br>1 (и более)  | Ошибка |
| №1<br>№2<br>№3               | да<br>да<br>да      | 0<br>1<br>0       | Сработает №2 |  
| №1<br>№2<br>№3               | да<br>да<br>да      | 0<br>1<br>1       | Ошибка |    
| №1<br>№2<br>№3               | да<br>да<br>да      | 0<br>2<br>0       | Ошибка |           

## Эмуляция REST сервисов

Алгоритм работы:

1. Поиск мока по URL/HTTP-verb/заголовков
2. Валидация body
3. Поиск state по предикату
4. Подстановка значений в шаблон ответа
5. Модификация state
6. Отдача response

### Конфигурация HTTP заглушек

HTTP заголовки валидируются на полное соответствие значений, лишние заголовки не являются ошибкой

Валидация тела запросы в HTTP заглушках может работать в следующих режимах:
* no_body - запрос должен быть без тела
* any_body - тело запроса должно быть не пустым, при этом никак не парсится и не проверяется
* raw - тело запроса никак не парсится и проверяется на полное соответствие с содержимым request.body
* json - тело запроса должно быть валидным JSON'ом и проверяется на соответствие с содержимым request.body
* xml - тело запроса должно быть валидным XML и проверяется на соответствие с содержимым request.body
* jlens - тело запроса должно быть валидным JSON'ом и валидируется по условиям, описаным в request.body
* xpath - тело запроса должно быть валидным XML и валидируется по условиям, описаным в request.body
* web_form - тело запроса должно быть в формате x-www-form-urlencoded и валидируется по условиям, описаным в request.body
* multipart - тело запроса должно быть в формате multipart/form-data. Правила валидации частей конфигурируются индивидуально (см. раздел ниже) 

ВНИМАНИЕ! multipart запросы необходимо выполнять на отдельный метод -
/api/mockingbird/execmp

Для ответов поддерживаются следующие режимы:
* raw
* json
* xml
* binary
* proxy
* json-proxy
* xml-proxy

Режимы request и response полностью независимы друг от друга (можно сконфигурировать ответ xml'ем на json запрос при желании, кроме режимов json-proxy и xml-proxy)

В поле delay можно передать корректный FiniteDuration не дольше 30 секунд

### Экстрация данных из URL
Бывает, что URL содержит какой-нибудь идентификатор не как параметр, а как непосредственно часть пути. В таких случаях становится невозможным
описать persistent заглушку из-за невозможности полного совпадения пути. На помощь приходит поле pathPattern, в которое можно передать регулярку,
на соответствие которой будет проверяться путь. Отмечу, что хоть сопоставление и производится в монге эффективным способом, злоупотребять этой
возможностью не стоит и при возможности сопоставления по полному совпадению не следует использовать pathPattern

Пример:
```javascript
{
  "name": "Sample stub",
  "scope": "persistent",
  "pathPattern": "/pattern/(?<id>\d+)",
  "method": "GET",
  "request": {
    "headers": {},
    "mode": "no_body",
    "body": {}
  },
  "response": {
    "code": 200,
    "mode": "json",
    "headers": {"Content-Type":  "application/json"},
    "body": {"id": "${pathParts.id}"}
  }
}
```
То, что нужно извлечь из пути, нужно делать _именованой_ группой, групп может быть сколько угодно, впоследствии на них можно ссылаться через `pathParts.<имя_группы>`

### Экстракторы
В некоторых случаях нужно подставить в ответ данные, которые невозможно извлечь простыми средствами. Для этих целей были добавлены экстракторы

#### Экстрактор xcdata

Достаёт значения из XML, лежащего в CDATA

конфигурация:
```javascript
{
  "type": "xcdata",
  "prefix": "/root/inner/tag", //Путь до тэга с CDATA
  "path": "/path/to" //Путь до нужного тэга
}
```

#### Экстрактор jcdata

Достаёт значения из JSON, лежащего в CDATA

конфигурация:
```javascript
{
  "type": "jcdata",
  "prefix": "/root/inner/tag", //Путь до тэга с CDATA
  "path": "path.to" //Путь до нужного значения
}
```

#### CDATA inlining
Иногда приходится иметь дело с запросами, в которых внутри CDATA лежит XML. В таких случаях можно заинлайнить содержимое DATA с помощью параметра `inlineCData` (поддерживается в `xpath` и `xml`)

### Примеры

#### Полное совпадение, режим json

```javascript
{
    "name": "Sample stub",
    "method": "POST",
    "path": "/pos-loans/api/cl/get_partner_lead_info",
    "state": {
      // Предикаты
    },
    "request": {
        "headers": {"Content-Type": "application/json"},
        "mode": "json",
        "body": {
            "trace_id": "42",
            "account_number": "228"
        }
    },
    "persist": {
      // Модификации состояния
    },
    "response": {
        "code": 200,
        "mode": "json",
        "body": {
            "code": 0,
            "credit_amount": 802400,
            "credit_term": 120,
            "interest_rate": 13.9,
            "partnum": "CL3.15"
        },
        "headers": {"Content-Type": "application/json"},
        "delay": "1 second"
    }
}
```

#### Полное совпадение, режим raw

```javascript
{
    "name": "Sample stub",
    "method": "POST",
    "path": "/pos-loans/api/evil/soap/service"
    "state": {
      // Предикаты
    },
    "request": {
        "headers": {"Content-Type": "application/xml"},
        "mode": "raw"
        "body": "<xml><request type=\"rqt\"></request></xml>"
    },
    "persist": {
      // Модификации состояния
    },
    "response": {
        "code": 200,
        "mode": "raw"
        "body": "<xml><response type=\"rqt\"></response></xml>",
        "headers": {"Content-Type": "application/xml"},
        "delay": "1 second"
    }
}
```

#### Валидация по условиям, режим jlens

```javascript
{
    "name": "Sample stub",
    "method": "POST",
    "path": "/pos-loans/api/cl/get_partner_lead_info",
    "state": {
      // Предикаты
    },
    "request": {
        "headers": {"Content-Type": "application/json"},
        "mode": "jlens",
        "body": {
            "meta.id": {"==": 42}
        }
    },
    "persist": {
      // Модификации состояния
    },
    "response": {
        "code": 200,
        "mode": "json",
        "body": {
            "code": 0,
            "credit_amount": 802400,
            "credit_term": 120,
            "interest_rate": 13.9,
            "partnum": "CL3.15"
        },
        "headers": {"Content-Type": "application/json"},
        "delay": "1 second"
    }
}
```

#### Валидация по условиям, режим xpath

ВНИМАНИЕ! НЕ ИСПОЛЬЗУЙТЕ НЕЙМСПЕЙСЫ В XPATH ВЫРАЖЕНИЯХ

```javascript
{
    "name": "Sample stub",
    "method": "POST",
    "path": "/pos-loans/api/cl/get_partner_lead_info",
    "state": {
      // Предикаты
    },
    "request": {
        "headers": {"Content-Type": "application/xml"},
        "mode": "xpath",
        "body": {
            "/payload/response/id": {"==": 42}
        },
        "extractors": {"name": {...}, ...} //опционально
    },
    "persist": {
      // Модификации состояния
    },
    "response": {
        "code": 200,
        "mode": "raw"
        "body": "<xml><response type=\"rst\"></response></xml>",
        "headers": {"Content-Type": "application/xml"},
        "delay": "1 second"
    }
}
```

#### Валидация по условиям, режим multipart

ВНИМАНИЕ! multipart запросы необходимо выполнять на отдельный метод -
/api/mockingbird/execmp

Режимы валидании part:
* `any` - значение никак не валидируется
* `raw` - полное соответствие
* `json` - полное соответствие, значение парсится как Json
* `xml` - полное соответствие, значение парсится как XML
* `urlencoded` - аналогично режиму `web_form` для валидации всего тела
* `jlens` - проверка Json по условиям
* `xpath` - проверка XML по условиям

```javascript
{
    "name": "Sample stub",
    "method": "POST",
    "path": "/test/multipart",
    "state": {
      // Предикаты
    },
    "request": {
        "headers": {},
        "mode": "multipart",
        "body": {
            "part1": {
              "mode": "json", //режим валидации
              "headers": {}, //заголовки part
              "value": {} //спецификация значения для валидатора
            },
            "part2": {
              ...
            }
        },
        "bypassUnknownParts": true //флаг, позволяющий игнорировать все partы, отсутвующие в спецификации валидатора
                                   //по умолчанию флаг включен, можно передавать только для отключения (false)
    },
    "persist": {
      // Модификации состояния
    },
    "response": {
        "code": 200,
        "mode": "json",
        "body": {
            "code": 0,
            "credit_amount": 802400,
            "credit_term": 120,
            "interest_rate": 13.9,
            "partnum": "CL3.15"
        },
        "headers": {"Content-Type": "application/json"},
        "delay": "1 second"
    }
}
```

#### Простое проксирование запроса

```javascript
{
  "name": "Simple proxy",
  "method": "POST",
  "path": "/pos-loans/api/cl/get_partner_lead_info",
  "state": {
      // Предикаты
  },
  "request": {
    // Спецификация запроса
  },
  "response": {
    "mode": "proxy",
    "uri": "http://some.host/api/cl/get_partner_lead_info"
  }
}
```

#### Проксирование с модификацией JSON ответа

```javascript
{
  "name": "Simple proxy",
  "method": "POST",
  "path": "/pos-loans/api/cl/get_partner_lead_info",
  "state": {
      // Предикаты
  },
  "request": {
    // Спецификация запроса, mode json или jlens
  },
  "response": {
    "mode": "json-proxy",
    "uri": "http://some.host/api/cl/get_partner_lead_info",
    "patch": {
      "field.innerField": "${req.someRequestField}"
    }
  }
}
```

#### Проксирование с модификацией XML ответа

```javascript
{
  "name": "Simple proxy",
  "method": "POST",
  "path": "/pos-loans/api/cl/get_partner_lead_info",
  "state": {
      // Предикаты
  },
  "request": {
    // Спецификация запроса, mode xml или xpath
  },
  "response": {
    "mode": "xml-proxy",
    "uri": "http://some.host/api/cl/get_partner_lead_info",
    "patch": {
      "/env/someTag": "${/some/requestTag}"
    }
  }
}
```

### DSL предикатов валидации JSON и XML

в режимах jlens и xpath поддерживается следующее:

```javascript
{
  "a": {"==": "some value"}, //полное соответствие
  "b": {"!=": "some value"}, //не равно
  "c": {">": 42} | {">=": 42} | {"<": 42} | {"<=": 42}, //сравнения, только для чисел, комбинируются
  "d": {"~=": "\d+"}, //сопоставление с regexp,
  "e": {"size": 10}, //длина, для массивов и строк
  "f": {"exists": true} //проверка существования
}
```
Ключами в таких объектах является либо путь в json ("a.b.[0].c") либо xpath ("/a/b/c")
Замечание: в данный момент функции сравнения могут некорректно работать с xpath, указывающими на XML атрибуты.
Обойти проблему модно проверкой на существование/несуществование:
```/tag/otherTag/[@attr='2']": {"exists": true}```

в режиме jlens дополнительно поддерживаются следующие операции:
```javascript
{
    "g": {"[_]": ["1", 2, true]}, //поле должно содержать одно из перечисленых значений
    "h": {"![_]": ["1", 2, true]}, //поле НЕ должно содержать ни одно из перечисленых знаечний
    "i": {"&[_]": ["1", 2, true]} // поле должно быть массивом и содержать все перечисленные значения (при этом порядок не важен)
}
```

в режиме xpath дополнительно поддерживаются следующие операции:
```javascript
  "/some/tag": {"cdata": {"==": "test"}}, //валидация на полное совпадение CDATA, аргумент должен быть СТРОКОЙ
  "/some/tag": {"cdata": {"~=": "\d+"}}, //валидация DATA регуляркой, аргумент должен быть СТРОКОЙ
  "/some/tag": {"jcdata": {"a": {"==": 42}}}, //валидируем содержимое CDATA как JSON, поддерживаются все доступные предикаты
  "/other/tag": {"xcdata": {"/b": {"==": 42}}} //валидируем содержимое CDATA как XML, поддерживаются все доступные предикаты
```

в режиме web_form поддерживаются ТОЛЬКО следующие операции:
`==`, `!=`, `~=`, `size`, `[_]`, `![_]`, `&[_]`

## Эмуляция GRPC сервисов

Как это устроено под капотом:
При создании мока вложеные в запрос proto файлы парсятся и преобразуются в json-представление protobuf схемы. В базе хранится именно json-представление,
а не оригинальный proto файл. Первое срабатывание мока может занимать немного больше времени, чем последующие, т.к. при первом срабатывании из
json-представляения генерируется декодер protobuf сообщений. После декодирования данные преобразуются в json, который проверяется json-предикатами,
задаными в поле requestPredicates. Если условия выполняются - то json из response.data (в режиме fill) сериализуется в protobuf и отдаётся в качестве ответа.

Алгоритм работы:

1. Поиск мока(-ов) по имени метода
2. Валидация body
3. Поиск state по предикату
4. Подстановка значений в шаблон ответа
5. Модификация state
6. Отдача response

### Конфигурация GRPC заглушек

```javascript
{
    "name": "Sample stub",
    "scope": "..",
    "service": "test",
    "methodName": "/pos-loans/api/cl/get_partner_lead_info",
    "seed": {
        "integrationId": "%{randomString(20)}" //пример
    },
    "state": {
      // Предикаты
    },
    "requestCodecs": "..", //proto-файл схемы запроса в base64
    "requestClass": "..", //имя типа запроса из proto файла
    "responseCodecs": "..", //proto-файл схемы ответа в base64
    "responseClass": "..", //имя типа ответа из proto файла
    "requestPredicates": {
        "meta.id": {"==": 42}
    },
    "persist": {
      // Модификации состояния
    },
    "response": {
        "mode": "fill",
        "data": {
            "code": 0,
            "credit_amount": 802400,
            "credit_term": 120,
            "interest_rate": 13.9,
            "partnum": "CL3.15"
        },
        "delay": "1 second"
    }
}
```

## Эмуляция шинных сервисов

Алгоритм работы:

1. Поиск мока по source
2. Поиск state по предикату
3. Валидация входящего сообщения
4. Подстановка значений в шаблон ответа
5. Модификация state
6. Отправка response
7. Выполнение колбеков (см. раздел "конфигурация колбеков")

### Конфигурация

[Работа с очередями](message-brokers.md)

### Конфигурация мока

Для input поддерживаются режимы:
* raw
* json
* xml
* jlens
* xpath

Для output поддерживаются режимы:
* raw
* json
* xml

```javascript
{
  "name": "Пришла весна", 
  "service": "test",
  "source": "rmq_example_autobroker_decision", //source из конфига
  "input": {
    "mode": .. //как для HTTP заглушек
    "payload": .. //как body для HTTP заглушек
  },
  "state": {
    // Предикаты
  },
  "persist": { //Опционально
    // Модификации состояния
  },
  "destination": "rmq_example_q1", // destination из конфига, опционально
  "output": { //Опционально  
    "mode": "raw",
    "payload": "..",
    "delay": "1 second"
  },
  "callback": { .. }
}
```

### Конфигурация колбеков

Для имитации поведения реального мира иногда нужно выполнить вызов HTTP сервиса (пример - забрать GBO когда приходит сообщение) или отправлять дополнительные сообщения в очереди. Для этого можно использовать колбеки. Результат вызова сервиса можно при необходимости распарсить и сохранить в состояние. Коллбеки используют состяние вызвавшего.

#### Вызов HTTP метода

Для request поддерживаются режимы
* no_body
* raw
* json
* xml

Для response поддерживаются режимы
* json
* xml

>Обратите внимание!
>В всю цепочку колбеков передаётся первоначальный стейт, он не изменяется блоком perist (!!!)

```javascript
{
  "type": "http",
  "request": {
    "url": "http://some.host/api/v2/peka",
    "method": "POST",
    "headers": {"Content-Type": "application/json"},
    "mode": "json",
    "body": {
      "trace_id": "42",
      "account_number": "228"
    }
  },
  "responseMode": "json" | "xml", //Обязательно только при наличии блока persist
  "persist": { //Опционально
    // Модификации состояния
  },
  "delay": "1 second", //Задержка ПЕРЕД выполнением колбека, опционально
  "callback": { .. } //Опционально
}
```

#### Отправка сообщения

Для output поддерживаются режимы:
* raw
* json
* xml

```javascript
{
  "type": "message",
  "destination": "rmq_example_q1", // destination из конфига
  "output": {
    "mode": "raw",
    "payload": ".."
  },
  "callback": { .. } //Опционально
}
```