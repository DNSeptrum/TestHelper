Aby uruchomić wtyczkę dla Intellij Idea należy:
- pobrać: build/distributions/testHelper-1.0-SNAPSHOT.zip
- przejść w Intellij Idea do File > settings > plugins 
- otworzyć koło zębate w prawym górnym rogu
- wybrać: Install Plugin from disk
- wybrać pobrany plik zip
- wcisnąć apply i zrestartować Intellij Idea

 Wtyczka kompatybilna jest z java 17 oraz wersją intellij Idea 2023.3.4
 Projekt na którym chcemy uzyć wtyczki powinnien posiadać następujace zależności:
 - hamcrest-all version 1.3
 - junit-jupiter-engine version 5.11.0-M2
 - mockito-core version 5.5.0
 - mockito-junit-jupiter version 5.5.0
 - mockito-inline version 5.2.0
 - assertj-core version 3.24.2
