import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts';

export default function SeatDistributionChart({ data }) {
    return (
        <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} layout="vertical" margin={{ top: 0, right: 10, left: 0, bottom: 0 }}>
                <XAxis type="number" hide />
                <YAxis type="category" dataKey="name" width={80} tick={{ fontSize: 11 }} />
                <Tooltip formatter={(value) => [`${value} people`, '']} />
                <Bar dataKey="value" radius={[0, 6, 6, 0]}>
                    {data.map((entry) => <Cell key={entry.name} fill={entry.fill} />)}
                </Bar>
            </BarChart>
        </ResponsiveContainer>
    );
}
