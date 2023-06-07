---
layout: default
title: Sheets selection
grand_parent: Main Features
parent: Book portions
nav_order: 2
---

## Sheets selection

An action launched at sheet level processes only that sheet.    
An action launched at book level, processes by default all the (valid) sheets of the book.
And this may be too much when what you want is to work only on a portion of the book
(for example to print or listen to just a movement or a few selected sheets).

You can specify a sheets selection, via pull-down menu `Book | Select sheets` which opens a
selection dialog as shown below:

|  Selection  |   Meaning    |
| ----------- | ------------ |
|![](../assets/images/specified_sheets_empty.png)| Blank specification: **All** sheets are selected|
|![](../assets/images/specified_sheets.png)| Specification says: 1, then 4 through 10|

Remarks:

- This is only a _specification_.
  To be really processed, each selected sheet must also be _valid_.
- A sheets specification must be written in a strict increasing order.
- Specifications "1,4-10" and "1,4,5,6,7,8,9,10" are equivalent.
- Since there is a gap in this example (sheets 2 and 3 are not selected), exporting the book
  to disk or plugin will result in at least two separate movements, one for sheet 1 and one
  for sheets 4-10.
- If the specification string is blank or null, all sheets are selected by default. [^empty_spec]
- A sheets specification can also be provided in batch via the `-sheets` argument on command line.
- The latest sheets specification is persisted in book `.omr` project file
  when specified via the GUI dialog but not when specified via the CLI in batch.

[^empty_spec]: In Audiveris 5.2, a null specification meant _all sheets_ while an empty specification meant _no sheets_. Starting with Audiveris 5.3, for the sake of consistency with the new "Split and Merge" feature, they now both mean the same thing: _**all sheets**_.
