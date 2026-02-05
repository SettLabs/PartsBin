# PartsBin

Needed something that can:
- Read/Alter Diptrace libs.
- Keep track of my current stock
- Check a bom against that current stock.
- Calculate the cost of said bom and multiples of it
- Subtract the bom from the stock or add an order to it.
- ...

Doesn't use web scraping (ToS issues) nor API (hard to get) but works by putting links in the clipboard and waiting for the user to copy the wanted info.

## Works
- Diptrace libs editing
- Bom upload to database
- Clipboard tool to gather sku's (mouser,lcsc) and prices (lcsc,mouser).
- Export to a csv file containing suggested quantities
  - Determines quantity based on moq and total price (allow overflow)
  - Picks higher moq if price difference is less than 1€ (for passives)
  - Allow for single pcb or aggregated of multiple for modular designs.
- Lcsc,Mouser order export upload to database 
