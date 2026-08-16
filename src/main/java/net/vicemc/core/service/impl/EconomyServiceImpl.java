package net.vicemc.core.service.impl;

import net.vicemc.api.model.Transaction;
import net.vicemc.api.model.TransactionResult;
import net.vicemc.api.service.EconomyService;
import net.vicemc.api.model.DepositHandler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SQLite-backed dollar ledger with deposit-side handlers (garnishment etc).
 */
public final class EconomyServiceImpl implements EconomyService {

    private final StorageServiceImpl storage;
    private final List<DepositHandler> handlers = new CopyOnWriteArrayList<>();

    public EconomyServiceImpl(StorageServiceImpl storage) {
        this.storage = storage;
    }

    @Override
    public double balance(UUID player) {
        ensureAccount(player);
        try (PreparedStatement ps = connection().prepareStatement("SELECT balance FROM accounts WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
            }
        } catch (SQLException ex) {
            log(ex);
        }
        return 0;
    }

    @Override
    public TransactionResult deposit(UUID player, double amount, String category) {
        if (amount <= 0) {
            return TransactionResult.fail(amount, category, "amount must be positive");
        }
        ensureAccount(player);
        double credited = amount;
        StringBuilder detail = new StringBuilder();
        for (DepositHandler handler : handlers) {
            double after = handler.apply(player, credited, category);
            if (after < credited) {
                if (detail.length() > 0) {
                    detail.append(", ");
                }
                detail.append(handler.description()).append(" -").append(String.format("%.2f", credited - after));
            }
            credited = after;
        }
        if (credited < 0) {
            credited = 0;
        }
        double balance = balance(player);
        double afterTotal = balance + credited;
        updateBalance(player, afterTotal);
        insertTransaction(player, credited, category, afterTotal);
        return new TransactionResult(true, amount, credited, category, detail.toString());
    }

    @Override
    public TransactionResult withdraw(UUID player, double amount, String category) {
        if (amount <= 0) {
            return TransactionResult.fail(amount, category, "amount must be positive");
        }
        ensureAccount(player);
        double balance = balance(player);
        if (balance < amount) {
            return TransactionResult.fail(amount, category, "insufficient funds");
        }
        double afterTotal = balance - amount;
        updateBalance(player, afterTotal);
        insertTransaction(player, -amount, category, afterTotal);
        return TransactionResult.ok(amount, category);
    }

    @Override
    public TransactionResult transfer(UUID from, UUID to, double amount, String category) {
        TransactionResult w = withdraw(from, amount, category);
        if (!w.success()) {
            return w;
        }
        deposit(to, amount, category);
        return w;
    }

    @Override
    public List<Transaction> history(UUID player) {
        return history(player, 100);
    }

    @Override
    public List<Transaction> history(UUID player, int limit) {
        ensureAccount(player);
        List<Transaction> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT id, uuid, amount, category, at, balance_after FROM transactions WHERE uuid = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, player.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Transaction(
                            rs.getLong(1),
                            UUID.fromString(rs.getString(2)),
                            rs.getDouble(3),
                            rs.getString(4),
                            rs.getLong(5),
                            rs.getDouble(6)));
                }
            }
        } catch (SQLException ex) {
            log(ex);
        }
        return result;
    }

    @Override
    public Map<UUID, Double> allBalances() {
        Map<UUID, Double> result = new LinkedHashMap<>();
        try (PreparedStatement ps = connection().prepareStatement("SELECT uuid, balance FROM accounts ORDER BY balance DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(UUID.fromString(rs.getString(1)), rs.getDouble(2));
                }
            }
        } catch (SQLException ex) {
            log(ex);
        }
        return result;
    }

    @Override
    public double totalSupply() {
        double total = 0;
        try (PreparedStatement ps = connection().prepareStatement("SELECT COALESCE(SUM(balance), 0) FROM accounts")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    total = rs.getDouble(1);
                }
            }
        } catch (SQLException ex) {
            log(ex);
        }
        return total;
    }

    @Override
    public List<Transaction> recentTransactions(int limit) {
        List<Transaction> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT id, uuid, amount, category, at, balance_after FROM transactions ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Transaction(
                            rs.getLong(1),
                            UUID.fromString(rs.getString(2)),
                            rs.getDouble(3),
                            rs.getString(4),
                            rs.getLong(5),
                            rs.getDouble(6)));
                }
            }
        } catch (SQLException ex) {
            log(ex);
        }
        return result;
    }

    @Override
    public Map<String, Double> categoryTotals() {
        return categoryTotals(null);
    }

    @Override
    public Map<String, Double> categoryTotals(UUID player) {
        Map<String, Double> result = new HashMap<>();
        String sql = "SELECT category, SUM(amount) FROM transactions"
                + (player == null ? "" : " WHERE uuid = ?")
                + " GROUP BY category ORDER BY category";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            if (player != null) {
                ps.setString(1, player.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getDouble(2));
                }
            }
        } catch (SQLException ex) {
            log(ex);
        }
        return result;
    }

    @Override
    public long transactionCount() {
        try (PreparedStatement ps = connection().prepareStatement("SELECT COUNT(*) FROM transactions")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException ex) {
            log(ex);
        }
        return 0;
    }

    @Override
    public void addDepositHandler(DepositHandler handler) {
        handlers.add(handler);
    }

    @Override
    public void removeDepositHandler(DepositHandler handler) {
        handlers.remove(handler);
    }

    private void ensureAccount(UUID player) {
        try (PreparedStatement ps = connection().prepareStatement(
                "INSERT OR IGNORE INTO accounts (uuid, balance, updated_at) VALUES (?, 0, ?)")) {
            ps.setString(1, player.toString());
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ex) {
            log(ex);
        }
    }

    private void updateBalance(UUID player, double value) {
        try (PreparedStatement ps = connection().prepareStatement(
                "UPDATE accounts SET balance = ?, updated_at = ? WHERE uuid = ?")) {
            ps.setDouble(1, value);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, player.toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            log(ex);
        }
    }

    private void insertTransaction(UUID player, double amount, String category, double balanceAfter) {
        try (PreparedStatement ps = connection().prepareStatement(
                "INSERT INTO transactions (uuid, amount, category, at, balance_after) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setDouble(2, amount);
            ps.setString(3, category == null ? "" : category);
            ps.setLong(4, System.currentTimeMillis());
            ps.setDouble(5, balanceAfter);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log(ex);
        }
    }

    private Connection connection() {
        return storage.connection();
    }

    private void log(SQLException ex) {
        System.err.println("[ViceCore] SQL error: " + ex.getMessage());
    }
}
