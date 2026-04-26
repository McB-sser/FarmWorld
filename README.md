# FarmWorld

FarmWorld ist ein Paper-Plugin fuer Minecraft 1.21.x, das drei getrennte Farmwelten ueber spezielle Portale bereitstellt: eine Farm-Oberwelt, einen Farm-Nether und ein Farm-End. Das Plugin ersetzt dabei nicht einfach nur normale Netherportale, sondern fuegt ein komplettes Zugangssystem mit Zeitlimit, Rueckkehr-Kompass, Claim-Zonen und automatischen Welt-Resets hinzu.

Die Grundidee ist einfach: Spieler betreten eine Farmwelt nicht dauerhaft, sondern fuer eine begrenzte Session. Die verfuegbare Zeit wird beim Eintritt aus den aktuellen Erfahrungsleveln berechnet. In der Farmwelt koennen Spieler einen eigenen sicheren Punkt markieren, dorthin bei spaeteren Besuchen zurueckkehren und ihre Zone gegen fremden Zugriff schuetzen. Nach fest definierten Reset-Terminen wird die jeweilige Farmwelt komplett neu erzeugt, damit Ressourcen regelmaessig frisch verfuegbar sind.

## Was das Plugin macht

Das Plugin erweitert einen Server um ein kontrolliertes Farmwelt-System mit folgenden Kernfunktionen:

- Drei Portaltypen fuer drei getrennte Welten:
  - Farm Oberwelt
  - Farm Nether
  - Farm End
- Eigene Portalrahmen je Welt-Typ:
  - Polierter Andesit -> Farm Oberwelt
  - Polierter Granit -> Farm Nether
  - Polierter Diorit -> Farm End
- Eintritt ueber aktivierte Portalrahmen statt ueber Vanilla-Mechanik allein
- Zeitbasierte Farm-Sessions
- Rueckkehr-Kompass fuer Teleport, Claim und Freigaben
- Spielerbezogene Claim-Zonen pro Farmwelt
- Automatisch gebaute Schutzhuetten an Eintrittspunkten
- Sichere Spawnpunktsuche innerhalb einer Weltgrenze
- Automatische Resets der Farmwelten nach Zeitplan
- Schutz vor normaler Portalnutzung innerhalb der Farmwelten

## Voraussetzungen

- Paper API 1.21.4
- Java 21
- Maven-Projekt mit Packaging `jar`

## Konzept im Ueberblick

Spieler bauen in der Hauptwelt ein Portal aus einem der drei vorgesehenen Rahmenmaterialien. Wird das Portal mit Feuerzeug oder Fire Charge aktiviert, erkennt das Plugin die Form, erzeugt ein echtes Portal und verknuepft es mit genau einer Farmwelt.

Beim Betreten eines solchen Portals wird kein klassisches Netherziel verwendet. Stattdessen:

1. Das Plugin erkennt den Portaltyp.
2. Es prueft, ob der Spieler genug Level fuer den Eintritt besitzt.
3. Es reserviert einen freien Inventarslot fuer den Rueckkehr-Kompass.
4. Es sucht einen sicheren Eintrittspunkt in der passenden Farmwelt.
5. Es teleportiert den Spieler in eine Session mit begrenzter Farmzeit.

## Portaltypen und Materialien

Die Portalart ergibt sich ausschliesslich aus dem kompletten Rahmenmaterial:

| Rahmenmaterial | Zielwelt | Interner Weltname | Reset-Regel |
| --- | --- | --- | --- |
| Polierter Andesit | Farm Oberwelt | `farm_overworld` | monatlich |
| Polierter Granit | Farm Nether | `farm_nether` | quartalsweise |
| Polierter Diorit | Farm End | `farm_end` | quartalsweise |

Wichtig:

- Der gesamte Rahmen muss aus genau einem gueltigen Material bestehen.
- Gemischte Rahmen werden nicht akzeptiert.
- Das Portalinnere darf aus Luft, Feuer oder bereits vorhandenem Portal bestehen.
- Die gueltige Innenflaeche liegt zwischen 2 und 21 Bloecken Breite sowie 3 bis 21 Bloecken Hoehe.

## So benutzt man das Plugin

### 1. Portal bauen

Baue einen Portalrahmen in Netherportal-Form, aber nicht aus Obsidian, sondern aus einem der drei Spezialmaterialien:

- Polierter Andesit fuer die Farm-Oberwelt
- Polierter Granit fuer den Farm-Nether
- Polierter Diorit fuer das Farm-End

Danach entzuendest du das Portal mit:

- Feuerzeug
- oder Fire Charge

Wenn die Form gueltig ist, wird das Portal aktiviert und einer Farmwelt zugeordnet.

### 2. Farmwelt betreten

Beim Betreten eines aktiven Portals prueft das Plugin:

- Hat der Spieler mindestens 5 Level?
- Ist mindestens 1 Inventarslot frei?
- Kann ein sicherer Zielpunkt gefunden werden?

Wenn alles passt, startet eine Farm-Session.

### 3. Zeitbudget verstehen

Das Zeitbudget berechnet sich direkt aus den Erfahrungsleveln beim Eintritt:

- 1 Level = 60 Sekunden Farmzeit
- Minimum fuer den Eintritt = 5 Level
- Beim Eintritt werden die aktuellen Level auf 0 gesetzt
- Verbleibende Zeit wird bei der Rueckkehr wieder in Level zurueckgegeben

Beispiel:

- Ein Spieler betritt die Farmwelt mit 17 Leveln.
- Er erhaelt 17 Minuten Farmzeit.
- Verlässt er die Welt mit 6 verbleibenden Minuten, bekommt er 6 Level zurueck.

### 4. Farmzeit in der Welt verlaengern

Waehren der Session sammelt das Plugin erhaltene Erfahrung nicht normal ein. Stattdessen wird gewonnene EXP in zusaetzliche Farmzeit umgerechnet.

- Jedes neu erreichte Level in der Session gibt 1 weitere Minute Farmzeit.
- Die normale EXP-Auszahlung wird dabei abgefangen und in Session-Zeit investiert.

Das sorgt dafuer, dass aktives Farmen den Aufenthalt verlaengern kann.

### 5. Rueckkehr in die Hauptwelt

Jeder Spieler erhaelt beim Eintritt automatisch einen speziellen Rueckkehr-Kompass. Dieser ist das zentrale Bedienwerkzeug waehrend einer Session.

Normale Rueckkehr:

- Rechtsklick mit dem Kompass startet einen 5-Sekunden-Countdown.
- Der Spieler muss dafuer still stehen bleiben.
- Bewegt sich der Spieler, wird der Countdown abgebrochen.
- Nach Ablauf erfolgt die Rueckteleportation zum gespeicherten Rueckkehrpunkt am Portal.

Automatische Rueckkehr:

- Wenn die Farmzeit auf 0 faellt, wird der Spieler automatisch zurueckteleportiert.
- Bei Plugin-Deaktivierung oder Welt-Reset werden aktive Spieler ebenfalls sauber aus der Farmwelt geholt.

## Der Rueckkehr-Kompass im Detail

Der Kompass kann nicht wie ein normales Item behandelt werden:

- Er kann nicht gedroppt werden.
- Er kann nicht frei in Container verschoben werden.
- Duplikate werden automatisch entfernt.
- Fehlt der Kompass waehrend einer aktiven Session, wird er neu erstellt.

Seine Funktionen:

- Rechtsklick: Rueckkehr-Countdown starten
- Ducken + Rechtsklick auf Block: Claim setzen
- Ducken + Linksklick: Claim loeschen
- Ducken + Rechtsklick auf Spieler: Zugriff auf Claim-Zone teilen

Wenn ein Claim gesetzt wurde, zeigt der Kompass ueber einen Lodestone-Zielpunkt auf den gesetzten Farm-Anker.

## Claim-System und Leitstein-Zonen

Jeder Spieler kann pro Farmwelt einen eigenen Claim setzen. Dieser Claim dient gleichzeitig als persoenlicher Wiedereintrittspunkt fuer spaetere Besuche.

### Claim setzen

So setzt du einen Claim:

1. Betritt die gewuenschte Farmwelt.
2. Nimm den Rueckkehr-Kompass in die Hand.
3. Halte Shift.
4. Rechtsklicke auf einen Block.

Dann passiert Folgendes:

- Das Plugin versucht einen sicheren Anchor-Punkt zu setzen.
- Der Punkt wird fuer diese Farmwelt gespeichert.
- Der Kompass richtet sich auf diesen Punkt aus.
- Fuer den Spieler wird dort eine geschuetzte Zone angelegt.

### Claim-Zone

Die Claim-Zone ist chunkbasiert und umfasst einen Radius von 3 Chunks um den gesetzten Mittelpunkt. Effektiv entsteht damit ein grosser geschuetzter Bereich rund um den Claim.

In dieser Zone gelten Schutzregeln:

- Fremde Spieler duerfen dort keine Bloecke abbauen.
- Fremde Spieler duerfen dort nicht mit Bloecken interagieren.
- Der Besitzer und freigegebene Spieler duerfen die Zone normal nutzen.
- Spieler sehen beim Betreten der Zone einen Hinweis im Chat.
- Die Zonengrenze wird ueber Partikel sichtbar gemacht, solange man sich darin befindet.

### Zugriff teilen

Der Besitzer einer Zone kann andere Spieler freigeben:

1. In der eigenen Leitstein-Zone stehen.
2. Den Rueckkehr-Kompass in der Haupthand halten.
3. Shift gedrueckt halten.
4. Rechtsklick auf den gewuenschten Spieler.

Danach bekommt der andere Spieler Zugriff auf diese Zone.

### Claim loeschen

Zum Entfernen des Claims:

1. Rueckkehr-Kompass in die Hand nehmen
2. Shift halten
3. Linksklick ausfuehren

Dann werden Claim, Schutzzone und gespeicherter Shelter fuer diese Farmwelt entfernt.

## Wiedereintritt und Spawnlogik

Das Plugin versucht beim Eintritt immer einen sicheren Punkt zu finden.

Prioritaet:

1. Bereits gesetzter persoenlicher Claim
2. Bereits gebauter Shelter am Claim
3. Sicherer Punkt in der Naehe des Claims
4. Zufaelliger sicherer Punkt in der Farmwelt

Sicher bedeutet unter anderem:

- innerhalb der Weltgrenze
- keine Lava oder andere direkte Schadensbloecke unter dem Spieler
- genug Platz fuer Koerper und Kopf
- kein ungeeigneter Nether-Bedrock-Spawn
- kein Monster-Spawner im Radius von 12 Bloecken

## Automatische Schutzhuette

Wenn ein Eintrittspunkt bestimmt wurde, baut das Plugin dort eine kleine Schutzhuette aus Cobblestone:

- 5x5 Grundflaeche
- Dach und Waende aus Cobblestone
- vier Tueren
- Fackel im Inneren

Diese Huette dient als sicherer Startpunkt, besonders wenn die Umgebung gefaehrlich oder unuebersichtlich ist. Der Shelter wird gespeichert und bei spaeteren Eintritten desselben Spielers in derselben Farmwelt wiederverwendet.

## Besondere Regeln in den Farmwelten

Das Plugin schraenkt Vanilla-Portalverhalten in Farmwelten bewusst ein:

- Normale Netherportale innerhalb einer Farmwelt sind deaktiviert.
- Normale Endportale innerhalb einer Farmwelt sind deaktiviert.
- Ein Farmwelt-Besuch endet immer ueber die Session-Logik des Plugins.

Dadurch bleibt der Rueckweg kontrolliert und Spieler koennen die Farmwelt nicht ueber normale Portalmechaniken umgehen.

## Creative- und Spectator-Verhalten

Spieler im Creative- oder Spectator-Modus werden speziell behandelt:

- unbegrenzte Farmzeit
- sofortige Rueckkehr ohne Countdown-Beschraenkung
- kein normales Level-Limit fuer den Eintritt
- Aktivierung und Verwaltung funktionieren trotzdem ueber die gleiche Portal- und Kompasslogik

## Resets der Farmwelten

Die Farmwelten werden automatisch geloescht und komplett neu erstellt. Dabei gehen Claims und Shelter in der betroffenen Welt verloren, damit die Welt wirklich frisch ist.

Reset-Zeitpunkte:

- Farm Oberwelt: jeden letzten Sonntag im Monat um 18:00 Uhr
- Farm Nether: jeden letzten Sonntag eines Quartalsmonats um 18:00 Uhr
- Farm End: jeden letzten Sonntag eines Quartalsmonats um 18:00 Uhr

Quartalsmonate sind:

- Januar
- April
- Juli
- Oktober

Beim Reset passiert:

1. Alle Spieler werden aus der betroffenen Farmwelt zurueckteleportiert.
2. Die Welt wird entladen.
3. Der komplette Weltordner wird geloescht.
4. Die Welt wird neu erstellt.
5. Claims, Shelter und Zonendaten dieser Welt werden entfernt.
6. Der naechste Reset-Termin wird neu berechnet.

## Befehl

Das Plugin besitzt aktuell einen Befehl:

### `/farmreset`

Zeigt die naechsten geplanten Reset-Termine aller Farmwelten an und nennt zusaetzlich den naechsten globalen Reset.

## Technische Hinweise

- Aktive Portale, Claims, Shelter und Rueckkehrpositionen werden in `claims.yml` gespeichert.
- Farmwelten haben eine Weltgrenze von 5000 Bloecken Durchmesser.
- Das Plugin rettet Spieler, die sich ohne gueltige Session in einer Farmwelt befinden, automatisch zurueck in eine sichere Welt.
- Beim Weltbeitritt nach einem Neustart werden haengengebliebene Spieler ebenfalls abgesichert.

## Typischer Ablauf fuer Spieler

1. Spezialportal aus passendem Material bauen
2. Portal mit Feuerzeug oder Fire Charge aktivieren
3. Portal betreten
4. Mit vorhandenen Leveln Farmzeit erhalten
5. In der Farmwelt Ressourcen farmen
6. Mit dem Rueckkehr-Kompass optional einen eigenen Claim setzen
7. Spaeter ueber denselben Claim erneut in der Welt landen
8. Mit dem Kompass zur Hauptwelt zurueckkehren

## Build

Das Projekt ist ein Maven-Plugin und kann klassisch gebaut werden mit:

```powershell
mvn clean package
```

Die fertige Plugin-Datei liegt anschliessend unter:

```text
target/farmworld-portals-1.0.0-SNAPSHOT.jar
```
