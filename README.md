# PartsBin

Needed something that can:
- Read/Alter diptrace libs.
- Keep track of my current stock
- Check a bom against that current stock.
- Calculate the cost of said bom and multiples of it
- Substract the bom from the stock or add an order to it.
- ...

Doesn't use webscraping (ToS issus) nor API (hard to get) but works by putting stuffing in the clipboard and waiting for the user to copy the wanted info.

## Status
- Diptrace libs editing
- Bom upload to database
- Clipboard tool to gather sku's (mouser,lcsc) and prices (lcsc).
- Database Views for minimum quantity ordering from lcsc based on moq and price for it.
