# Banking ATM Simulation System

A Java-based ATM simulation project built using **Java Swing** for the graphical user interface and **MySQL** for database management. It demonstrates basic banking operations with account management and transaction tracking.

## Features

- **User Registration & Login:** Create an account and log in using card details and PIN.
- **Balance Enquiry:** Check the current account balance.
- **Cash Deposit:** Deposit money into an account.
- **Cash Withdrawal:** Withdraw money with balance and withdrawal-limit validation.
- **PIN Management:** Change the account PIN.
- **Transaction History:** View account activities, including failed operations.
- **ATM Cash Management:** Simulate available ATM cash and enforce withdrawal limits.

## Tech Stack

- **Language:** Java
- **GUI:** Java Swing
- **Database:** MySQL
- **Database Connectivity:** JDBC

## Database Design

The database is designed around four tables:

- `accounts` – stores account details and balances.
- `transactions` – records account activities and their status.
- `daily_withdrawals` – tracks daily withdrawal totals.
- `atm_settings` – stores ATM cash availability and the daily withdrawal limit.

### Default Configuration

- Initial account balance: ₹500
- Initial ATM cash: ₹1,00,000
- Daily withdrawal limit: ₹10,000 per account
- Maximum incorrect PIN attempts: 3 per login session

## Setup

1. Install Java JDK and MySQL.
2. Create the `banking_atm` database and tables using the project's SQL script.
3. Configure the database connection credentials.
4. Add the MySQL JDBC driver to the project.
5. Compile and run the Java application.

## Purpose

This project demonstrates **object-oriented programming, GUI development, JDBC connectivity, relational database design, and basic banking transaction logic** using Java and MySQL.
