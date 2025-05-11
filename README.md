# JavaToJPHP
Automatic converter of .jar library to .dnbundle extension for JPHP/DevelNext

пока что поддерживает создание PHP SDK с совместимыми модификаторами доступа (static/non static/only public methods) и типами данных (ArrayMemory,Memory,List,Long,Int,String,Bool,Environment,Void), а так-же создание java-обёрток для будущего пакетного расширения JPHP.

# текущие проблемы/баги:
- нет обработок overload методов
- Отсутствие обработки нестатических методов корректно
- типы данных обрабатываются примитивно
